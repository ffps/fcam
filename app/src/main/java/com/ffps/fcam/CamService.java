package com.ffps.fcam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.view.OrientationEventListener;
import android.view.WindowManager;
import android.os.PowerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CamService extends Service implements Camera.PreviewCallback {

    public static volatile boolean running = false;
    public static volatile boolean camOk = false;
    public static volatile String lastError = null;

    private static final int ID_RUN = 1;
    private static final int ID_SHOT = 2;
    private static final int ID_ERR = 3;
    private static final String CH_RUN = "fcam_run";
    private static final String CH_SHOT = "fcam_shot";
    private static final int PIXEL_DIFF = 25;      // порог разницы яркости точки
    private static final int STEP = 4;             // прореживание кадра при анализе
    private static final long ANALYZE_EVERY_MS = 200;
    private static final long SETTLE_MS = 1500;    // пауза после старта превью (автоэкспозиция)

    public static void start(Context c) {
        Intent i = new Intent(c, CamService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, CamService.class));
    }

    private Prefs cfg;
    private HandlerThread ht;
    private Handler ch;                 // все операции с камерой выполняются в этом потоке
    private Camera cam;
    private SurfaceTexture tex;
    private HttpCtl http;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wifiLock;
    private NotificationManager nm;
    private OrientationEventListener orient;
    private volatile int lastOrientation = -1;   // градусы по часовой от естественного положения, -1 - неизвестно
    private Camera.CameraInfo camInfo;
    private int appliedRot = -1;

    private volatile boolean camReady = false;
    private volatile boolean shooting = false;
    private long shootStart;
    private long lastSeries = 0;
    private long lastAnalyze = 0;
    private long settleUntil = 0;
    private int pw, ph;
    private byte[] buf;
    private byte[] cur, prev;
    private boolean havePrev = false;
    private int shotIdx;
    private String tag = "m";
    private String stamp;
    private String status = "";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannels();
        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o != ORIENTATION_UNKNOWN) lastOrientation = o;
            }
        };
        if (orient.canDetectOrientation()) orient.enable();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        cfg = Prefs.load(this);
        lastError = null;
        status = "Работает, порт " + cfg.port;
        try {
            startForegroundCompat(buildRunNotification());
        } catch (Exception e) {
            try {      // повтор без типа сервиса
                startForeground(ID_RUN, buildRunNotification());
            } catch (Exception e2) {      // нет разрешения на камеру / запрет запуска из фона
                lastError = "startForeground: " + e2;
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        if (!cfg.enabled) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (ht == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fcam:service");
            wl.acquire();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fcam:wifi");
                wifiLock.acquire();
            } catch (Exception ignored) {
            }
            ht = new HandlerThread("fcam-cam");
            ht.start();
            ch = new Handler(ht.getLooper());
            ch.post(openRunnable);
            http = new HttpCtl(cfg.port, cfg.password, new HttpCtl.Cb() {
                @Override
                public int onShot() {
                    if (!camReady) return 2;
                    if (shooting) return 1;
                    ch.post(new Runnable() {
                        @Override
                        public void run() {
                            startSeries("e");
                        }
                    });
                    return 0;
                }

                @Override
                public String onList() {
                    return listJson();
                }

                @Override
                public File onIndex() {
                    return new File(photoDir(), "index.html");
                }

                @Override
                public File onPhoto(String name) {
                    return findPhoto(name);
                }

                @Override
                public void onError(String msg) {
                    lastError = msg;
                    updateStatus(msg);
                }
            });
            http.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        camOk = false;
        if (orient != null) orient.disable();
        if (http != null) http.shutdown();
        if (ch != null) {
            ch.removeCallbacksAndMessages(null);
            ch.post(new Runnable() {
                @Override
                public void run() {
                    closeCam();
                    Looper l = Looper.myLooper();
                    if (l != null) l.quit();
                }
            });
        }
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    // ---------- уведомления ----------

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Работа", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(CH_SHOT, "Снимки", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, channel);
        return new Notification.Builder(this);
    }

    private Notification buildRunNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        Notification.Builder b = builder(CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("fCam")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(ID_RUN, n);
        }
    }

    private void updateStatus(String s) {
        status = s;
        nm.notify(ID_RUN, buildRunNotification());
    }

    private void notifyShot(String file, int n, int total) {
        Notification.Builder b = builder(CH_SHOT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("fCam: снимок " + n + " из " + total)
                .setContentText(file)
                .setAutoCancel(true);
        nm.notify(ID_SHOT, b.build());
    }

    // ---------- камера ----------

    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            openCam();
        }
    };

    private void openCam() {
        try {
            int n = Camera.getNumberOfCameras();
            int idx = cfg.camera < n ? cfg.camera : 0;
            cam = Camera.open(idx);
            camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(idx, camInfo);
            appliedRot = -1;
            Camera.Parameters p = cam.getParameters();

            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width >= 320 && s.height >= 240) {
                    if (best == null || s.width * s.height < best.width * best.height) best = s;
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            p.setPreviewFormat(ImageFormat.NV21);

            Camera.Size pic = null;
            for (Camera.Size s : p.getSupportedPictureSizes()) {
                if (pic == null || (long) s.width * s.height > (long) pic.width * pic.height) pic = s;
            }
            if (pic != null) p.setPictureSize(pic.width, pic.height);
            p.setJpegQuality(90);

            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            cam.setParameters(p);

            Camera.Size ps = cam.getParameters().getPreviewSize();
            pw = ps.width;
            ph = ps.height;
            buf = new byte[pw * ph * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8];
            tex = new SurfaceTexture(0);
            cam.setPreviewTexture(tex);
            try {      // снижаем частоту превью до минимально достаточной для анализа (~5 к/с)
                Camera.Parameters p2 = cam.getParameters();
                int[] bestR = null;
                for (int[] r : p2.getSupportedPreviewFpsRange()) {
                    if (r[1] >= 5000 && (bestR == null || r[1] < bestR[1]
                            || (r[1] == bestR[1] && r[0] > bestR[0]))) bestR = r;
                }
                if (bestR != null) {
                    p2.setPreviewFpsRange(bestR[0], bestR[1]);
                    cam.setParameters(p2);
                }
            } catch (Exception ignored) {
            }
            startPreview();
            camReady = true;
            camOk = true;
            updateStatus("Работает, порт " + cfg.port);
        } catch (Exception e) {
            camReady = false;
            camOk = false;
            closeCam();
            updateStatus("Камера недоступна, повтор через 10 с");
            if (ch != null) ch.postDelayed(openRunnable, 10000);
        }
    }

    private void closeCam() {
        camReady = false;
        try {
            if (cam != null) {
                cam.setPreviewCallbackWithBuffer(null);
                cam.stopPreview();
                cam.release();
            }
        } catch (Exception ignored) {
        }
        cam = null;
        try {
            if (tex != null) tex.release();
        } catch (Exception ignored) {
        }
        tex = null;
    }

    private void startPreview() {
        cam.setPreviewCallbackWithBuffer(this);
        cam.addCallbackBuffer(buf);
        cam.startPreview();
        settleUntil = System.currentTimeMillis() + SETTLE_MS;
        havePrev = false;
    }

    // ---------- обнаружение движения ----------

    @Override
    public void onPreviewFrame(byte[] data, Camera c) {
        try {
            long now = System.currentTimeMillis();
            if (shooting && now - shootStart > 30000) {   // серия зависла
                shooting = false;
            }
            if (!shooting && now >= settleUntil && now - lastAnalyze >= ANALYZE_EVERY_MS) {
                lastAnalyze = now;
                analyze(data, now);
            }
        } finally {
            if (cam != null) cam.addCallbackBuffer(data);
        }
    }

    private void analyze(byte[] data, long now) {
        int cw = pw / STEP, chh = ph / STEP;
        int total = cw * chh;
        if (cur == null || cur.length != total) {
            cur = new byte[total];
            prev = new byte[total];
            havePrev = false;
        }
        int k = 0;
        for (int y = 0; y < chh; y++) {
            int row = y * STEP * pw;
            for (int x = 0; x < cw; x++) {
                cur[k++] = data[row + x * STEP];
            }
        }
        if (havePrev) {
            int diff = 0;
            for (int i = 0; i < total; i++) {
                int d = (cur[i] & 0xFF) - (prev[i] & 0xFF);
                if (d < 0) d = -d;
                if (d > PIXEL_DIFF) diff++;
            }
            float pct = diff * 100f / total;
            if (pct >= cfg.threshold && now - lastSeries >= cfg.minGap * 1000L) {
                startSeries("m");
            }
        }
        byte[] t = prev;
        prev = cur;
        cur = t;
        havePrev = true;
    }

    // ---------- серия кадров ----------

    private void startSeries(String t) {
        if (shooting || !camReady) return;
        shooting = true;
        shootStart = System.currentTimeMillis();
        lastSeries = shootStart;
        tag = t;
        shotIdx = 0;
        stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        applyRotation();
        shoot();
    }

    /** Угол поворота JPEG (камера записывает его в EXIF или поворачивает сама) по ориентации устройства. */
    private int jpegRotation() {
        if (camInfo == null) return 0;
        int o = lastOrientation;
        if (o < 0) {      // датчика нет или положение неизвестно - берём поворот экрана
            int rot = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            o = (360 - rot * 90) % 360;
        }
        o = ((o + 45) / 90 * 90) % 360;
        if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return (camInfo.orientation - o + 360) % 360;
        return (camInfo.orientation + o) % 360;
    }

    private void applyRotation() {
        try {
            int r = jpegRotation();
            if (r != appliedRot) {
                Camera.Parameters p = cam.getParameters();
                p.setRotation(r);
                cam.setParameters(p);
                appliedRot = r;
            }
        } catch (Exception ignored) {
        }
    }

    private final Runnable shotRunnable = new Runnable() {
        @Override
        public void run() {
            shoot();
        }
    };

    private void shoot() {
        try {
            cam.takePicture(null, null, jpeg);
        } catch (Exception e) {
            finishSeries();
        }
    }

    private final Camera.PictureCallback jpeg = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera c) {
            String name = stamp + "_" + tag + (shotIdx + 1) + ".jpg";
            boolean ok = save(name, data);
            shotIdx++;
            if (ok) notifyShot(name, shotIdx, cfg.count);
            try {
                startPreview();
            } catch (Exception e) {
                finishSeries();
                camReady = false;
                closeCam();
                ch.post(openRunnable);
                return;
            }
            if (shotIdx < cfg.count) {
                long delay = Math.max(300L, (long) (cfg.interval * 1000));
                ch.postDelayed(shotRunnable, delay);
            } else {
                finishSeries();
            }
        }
    };

    private void finishSeries() {
        shooting = false;
        havePrev = false;
        settleUntil = System.currentTimeMillis() + SETTLE_MS;
    }

    public static File photoDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "fCam");
    }

    private boolean save(String name, byte[] data) {
        try {
            File dir = photoDir();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs");
            File f = new File(dir, name);
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(data);
            } finally {
                o.close();
            }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return true;
        } catch (Exception e) {
            Notification.Builder b = builder(CH_SHOT)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("fCam: снимок не сохранён")
                    .setContentText("Нет доступа к DCIM/fCam, проверьте разрешения")
                    .setAutoCancel(true);
            nm.notify(ID_ERR, b.build());
            return false;
        }
    }

    private static java.util.ArrayList<File> photos() {
        java.util.ArrayList<File> list = new java.util.ArrayList<File>();
        File[] files = photoDir().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".jpg")) list.add(f);
            }
        }
        java.util.Collections.sort(list);
        return list;
    }

    /** Файл для /api/photo: только по имени. */
    private static File findPhoto(String name) {
        if (name == null || name.length() == 0) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.startsWith(".")
                || !name.toLowerCase(Locale.US).endsWith(".jpg")) return null;
        File f = new File(photoDir(), name);
        return f.isFile() ? f : null;
    }

    private static String jq(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** Ответ на /api/list. */
    private String listJson() {
        File dir = photoDir();
        long total = 0, free = 0;
        try {
            File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            StatFs st = new StatFs((dir.exists() ? dir : root).getAbsolutePath());
            long bs = st.getBlockSize();
            total = bs * st.getBlockCount();
            free = bs * st.getAvailableBlocks();
        } catch (Exception ignored) {
        }
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"total_bytes\": ").append(total).append(",\n");
        sb.append("  \"free_bytes\": ").append(free).append(",\n");
        java.util.ArrayList<File> ph = photos();
        if (ph.isEmpty()) {
            sb.append("  \"files\": []\n");
        } else {
            sb.append("  \"files\": [\n");
            for (int i = 0; i < ph.size(); i++) {
                File f = ph.get(i);
                sb.append("    {\"name\": ").append(jq(f.getName()))
                        .append(", \"size\": ").append(f.length())
                        .append(", \"time\": ").append(jq(df.format(new Date(f.lastModified()))))
                        .append('}').append(i < ph.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  ]\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
