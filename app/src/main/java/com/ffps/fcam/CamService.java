package com.ffps.fcam;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.wifi.WifiManager;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.OrientationEventListener;
import android.view.WindowManager;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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
    private long rebootNext = -1;       // следующее время автоперезагрузки (мс), -1 - нет
    private long lastWall, lastElapsed; // для обнаружения скачка системных часов
    private long serviceStart = 0;      // SystemClock.elapsedRealtime() на момент запуска сервиса
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
            serviceStart = SystemClock.elapsedRealtime();
            rebootInit();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fcam:service");
            wl.acquire();
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fcam:wifi");
                wifiLock.acquire();
            } catch (Exception ignored) {
            }
            startWorkers(0);
        }
        return START_STICKY;
    }

    private final Handler mainH = new Handler(Looper.getMainLooper());

    private void startWorkers(long camDelayMs) {
        ht = new HandlerThread("fcam-cam");
        ht.start();
        ch = new Handler(ht.getLooper());
        ch.postDelayed(openRunnable, camDelayMs);
        ch.post(new Runnable() {
            @Override
            public void run() {
                pruneThumbs();
            }
        });
        http = new HttpCtl(cfg.port, cfg.password, new HttpCtl.Cb() {
            @Override
            public int onShot() {
                final Handler hh = ch;
                if (!camReady || hh == null) return 2;
                if (shooting) return 1;
                hh.post(new Runnable() {
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
            public int onDelete(String name) {
                File f = findPhoto(name);
                if (f == null) return 1;
                return deletePhotoFile(f) ? 0 : 2;      // успех только если файла действительно не осталось в каталоге
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
            public File onThumb(String name) {
                return makeThumb(name);
            }

            @Override
            public String onInfo() {
                return infoJson();
            }

            @Override
            public String onReboot() {
                String m = RebootControl.method(CamService.this);
                if ("none".equals(m)) return null;
                // ответ клиенту уходит сразу, сама перезагрузка - через 1.5 с
                mainH.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        RebootControl.perform(CamService.this);
                    }
                }, 1500);
                return m;
            }

            @Override
            public String onConfigGet() {
                return configJson();
            }

            @Override
            public String onConfigSet(String body) {
                return applyConfig(body);
            }

            @Override
            public void onRestart() {
                mainH.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restartInternal();
                    }
                }, 600);
            }

            @Override
            public void onError(String msg) {
                lastError = msg;
                updateStatus(msg);
            }
        });
        http.start();
    }

    private void stopWorkers() {
        if (http != null) {
            http.shutdown();
            http = null;
        }
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
        ch = null;
        ht = null;
    }

    /** Применение новых настроек без остановки самого сервиса. */
    private void restartInternal() {
        if (!running) return;
        stopWorkers();
        cfg = Prefs.load(this);
        shooting = false;
        camReady = false;
        camOk = false;
        lastError = null;
        status = "Работает, порт " + cfg.port;
        nm.notify(ID_RUN, buildRunNotification());
        startWorkers(1000);      // камере нужно время, чтобы освободиться
        rebootInit();
    }

    @Override
    public void onDestroy() {
        running = false;
        camOk = false;
        if (orient != null) orient.disable();
        mainH.removeCallbacksAndMessages(null);
        stopWorkers();
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
            if (lastError == null) updateStatus("Работает, порт " + cfg.port);
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
                Handler h1 = ch;
                if (h1 != null) h1.post(openRunnable);
                return;
            }
            if (shotIdx < cfg.count) {
                long delay = Math.max(300L, (long) (cfg.interval * 1000));
                Handler h2 = ch;
                if (h2 != null) h2.postDelayed(shotRunnable, delay);
                else finishSeries();
            } else {
                finishSeries();
                cleanupOld();
                pruneThumbs();
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

    /**
     * Убирает записи об удалённых файлах из медиа-базы. Нельзя вызывать MediaScannerConnection.scanFile для
     * удалённого файла: на старых Android (замечено на 4.2) сканер создаёт пустой файл с тем же именем заново.
     */
    private void scanDeleted(String[] paths) {
        for (String p : paths) {
            try {
                getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA + "=?", new String[]{p});
            } catch (Exception ignored) {
            }
        }
    }

    /** Журнал последнего удаления фото (виден в /api/info как deleteLog). */
    private volatile String deleteLog = "";

    /** Есть ли файл в списке каталога (readdir, а не stat: на старых Android их ответы могут расходиться). */
    private static boolean listed(File f) {
        String[] names = f.getParentFile().list();
        if (names != null) {
            for (String n : names) {
                if (n.equals(f.getName())) return true;
            }
        }
        return false;
    }

    /**
     * Удаляет файл фото и проверяет результат по списку каталога. Если файл остался (на некоторых старых Android
     * File.delete() возвращает true, а запись в каталоге живёт дальше), пробуем переименование, удаление через
     * медиа-провайдер и команду rm. Что сработало, записывается в deleteLog. Возвращает true, если файла больше нет.
     * Список каталога читается один раз в обычном случае (когда File.delete() сразу сработал).
     */
    private boolean deletePhotoFile(File f) {
        String path = f.getAbsolutePath();
        StringBuilder lg = new StringBuilder();
        lg.append("canWrite=").append(f.canWrite()).append(" dirCanWrite=").append(f.getParentFile().canWrite())
                .append(" size=").append(f.length()).append(" | ");
        boolean d = f.delete();
        boolean still = listed(f);
        lg.append("delete=").append(d).append(" listed=").append(still);
        if (still) {
            File tmp = new File(f.getParentFile(), f.getName() + ".del");
            boolean r = f.renameTo(tmp);
            boolean d2 = tmp.delete();
            still = listed(f);
            lg.append(" | rename=").append(r).append(" delete2=").append(d2).append(" listed=").append(still);
        }
        if (still) {
            int rows = -1;
            try {
                rows = getContentResolver().delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Images.Media.DATA + "=?", new String[]{path});
            } catch (Exception e) {
                lg.append(" | mediastore error ").append(e);
            }
            still = listed(f);
            lg.append(" | mediastore rows=").append(rows).append(" listed=").append(still);
        }
        if (still) {
            // последняя попытка: системная команда rm, заодно её сообщение об ошибке подскажет причину
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"rm", path});
                java.io.InputStream es = pr.getErrorStream();
                byte[] buf = new byte[300];
                int n = es.read(buf);
                int code = pr.waitFor();
                lg.append(" | rm exit=").append(code);
                if (n > 0) lg.append(" err=").append(new String(buf, 0, n).trim());
            } catch (Exception e) {
                lg.append(" | rm error ").append(e);
            }
            still = listed(f);
            lg.append(" listed=").append(still);
        }
        if (!still) {
            deleteThumb(f.getName());
            scanDeleted(new String[]{path});
        }
        deleteLog = path + ": " + lg;
        return !still;
    }

    /** Удаляет фото старше cfg.keepDays суток (0 - не удалять). */
    private void cleanupOld() {
        if (cfg.keepDays <= 0) return;
        try {
            long limit = System.currentTimeMillis() - cfg.keepDays * 86400000L;
            for (File f : photos()) {
                if (f.lastModified() < limit) deletePhotoFile(f);
            }
        } catch (Exception ignored) {
        }
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
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
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

    // ---------- миниатюры ----------

    private static final int THUMB_SIZE = 320;
    private final Object thumbLock = new Object();

    private File thumbDir() {
        return new File(getCacheDir(), "thumbs");
    }

    private void deleteThumb(String name) {
        try {
            new File(thumbDir(), name).delete();
        } catch (Exception ignored) {
        }
    }

    /** Удаляет миниатюры, для которых больше нет фото (например, фото удалили снаружи). */
    private void pruneThumbs() {
        try {
            File[] ts = thumbDir().listFiles();
            if (ts == null || ts.length == 0) return;
            java.util.HashSet<String> names = new java.util.HashSet<String>();
            for (File f : photos()) names.add(f.getName());
            synchronized (thumbLock) {      // не трогаем файл, который сейчас создаётся
                for (File t : ts) {
                    if (!names.contains(t.getName())) t.delete();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static int exifDegrees(File f) {
        try {
            int o = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Миниатюра фото (кэшируется в cache/thumbs); если создать не удалось - оригинал. */
    private File makeThumb(String name) {
        File src = findPhoto(name);
        if (src == null) return null;
        File dir = thumbDir();
        File t = new File(dir, src.getName());
        if (t.isFile() && t.lastModified() >= src.lastModified()) return t;
        synchronized (thumbLock) {      // по одной штуке, чтобы не перегружать слабое устройство
            if (t.isFile() && t.lastModified() >= src.lastModified()) return t;
            try {
                dir.mkdirs();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(src.getAbsolutePath(), o);
                int max = Math.max(o.outWidth, o.outHeight);
                if (max <= 0) return src;
                int sample = 1;
                while (max / (sample * 2) >= THUMB_SIZE) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(src.getAbsolutePath(), o);
                if (b == null) return src;
                float sc = (float) THUMB_SIZE / Math.max(b.getWidth(), b.getHeight());
                Matrix m = new Matrix();
                if (sc < 1f) m.postScale(sc, sc);
                int deg = exifDegrees(src);
                if (deg != 0) m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                File tmp = new File(dir, src.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    r.compress(Bitmap.CompressFormat.JPEG, 75, out);
                } finally {
                    out.close();
                }
                r.recycle();
                if (!tmp.renameTo(t)) return src;
                return t;
            } catch (Throwable e) {      // в том числе OutOfMemoryError
                return src;
            }
        }
    }

    // ---------- настройки по HTTP ----------

    private static String[] cameraNames() {
        int n = 0;
        try {
            n = Camera.getNumberOfCameras();
        } catch (Exception ignored) {
        }
        String[] r = new String[n];
        for (int i = 0; i < n; i++) {
            String kind = "";
            try {
                Camera.CameraInfo ci = new Camera.CameraInfo();
                Camera.getCameraInfo(i, ci);
                kind = ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? " (фронтальная)" : " (задняя)";
            } catch (Exception ignored) {
            }
            r[i] = "Камера " + i + kind;
        }
        return r;
    }

    private String configJson() {
        Prefs p = Prefs.load(this);
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"autostart\": ").append(p.autostart).append(",\n");
        sb.append("  \"camera\": ").append(p.camera).append(",\n");
        sb.append("  \"threshold\": ").append(p.threshold).append(",\n");
        sb.append("  \"min_gap\": ").append(p.minGap).append(",\n");
        sb.append("  \"count\": ").append(p.count).append(",\n");
        sb.append("  \"interval\": ").append(p.interval).append(",\n");
        sb.append("  \"keep_days\": ").append(p.keepDays).append(",\n");
        sb.append("  \"reboot_cron\": ").append(jq(p.rebootCron == null ? "" : p.rebootCron)).append(",\n");
        sb.append("  \"port\": ").append(p.port).append(",\n");
        sb.append("  \"password\": ").append(jq(p.password)).append(",\n");
        sb.append("  \"cameras\": [");      // справочно, при записи игнорируется
        String[] names = cameraNames();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(jq(names[i]));
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    /** Меняет только те настройки, что есть в JSON. null - успех, иначе текст ошибки. */
    private String applyConfig(String body) {
        JSONObject j;
        try {
            j = new JSONObject(body);
        } catch (Exception e) {
            return "Некорректный JSON";
        }
        Prefs p = Prefs.load(this);
        try {
            if (j.has("autostart")) p.autostart = j.getBoolean("autostart");
            if (j.has("camera")) p.camera = j.getInt("camera");
            if (j.has("threshold")) p.threshold = (float) j.getDouble("threshold");
            if (j.has("min_gap")) p.minGap = j.getInt("min_gap");
            if (j.has("count")) p.count = j.getInt("count");
            if (j.has("interval")) p.interval = (float) j.getDouble("interval");
            if (j.has("keep_days")) p.keepDays = j.getInt("keep_days");
            if (j.has("reboot_cron")) p.rebootCron = j.getString("reboot_cron").trim();
            if (j.has("port")) p.port = j.getInt("port");
            if (j.has("password")) p.password = j.getString("password");
        } catch (JSONException e) {
            return "Неверный тип значения: " + e.getMessage();
        }
        String err = p.error();
        if (err != null) return err;
        p.enabled = true;
        p.save(this);
        return null;
    }

    // ---------- автоперезагрузка по расписанию ----------

    private static final long REBOOT_MIN_UPTIME_MS = 10 * 60 * 1000L;   // защита от перезагрузочной петли
    private static final long REBOOT_LATE_MS = 90 * 1000L;              // насколько позже минуты ещё можно сработать

    /** Читает расписание из настроек и запускает проверку раз в 10 секунд. */
    private void rebootInit() {
        mainH.removeCallbacks(rebootTick);
        rebootNext = -1;
        String spec = cfg.rebootCron == null ? "" : cfg.rebootCron.trim();
        if (spec.length() == 0 || !Schedule.isValid(spec)) return;
        lastWall = System.currentTimeMillis();
        lastElapsed = SystemClock.elapsedRealtime();
        rebootNext = Schedule.next(spec, lastWall);
        mainH.postDelayed(rebootTick, 10000);
    }

    private final Runnable rebootTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            String spec = cfg.rebootCron == null ? "" : cfg.rebootCron.trim();
            long wall = System.currentTimeMillis();
            long el = SystemClock.elapsedRealtime();
            // системные часы перевели: wall и elapsed разошлись, время считаем заново и не срабатываем
            boolean jumped = Math.abs((wall - lastWall) - (el - lastElapsed)) > 5000;
            lastWall = wall;
            lastElapsed = el;
            if (jumped) {
                rebootNext = Schedule.next(spec, wall);
            } else if (rebootNext > 0 && wall >= rebootNext) {
                boolean fresh = wall - rebootNext <= REBOOT_LATE_MS;
                rebootNext = Schedule.next(spec, wall);      // один раз на подходящую минуту
                if (fresh && el >= REBOOT_MIN_UPTIME_MS && !"none".equals(RebootControl.method(CamService.this))) {
                    RebootControl.perform(CamService.this);
                }
            }
            mainH.postDelayed(this, 10000);
        }
    };

    // ---------- сведения о системе (/api/info) ----------

    /** {всего, свободно} в байтах для раздела, где лежит DCIM. */
    private long[] storage() {
        long total = 0, free = 0;
        try {
            File dir = photoDir();
            File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            StatFs st = new StatFs((dir.exists() ? dir : root).getAbsolutePath());
            long bs = st.getBlockSize();
            total = bs * st.getBlockCount();
            free = bs * st.getAvailableBlocks();
        } catch (Exception ignored) {
        }
        return new long[]{total, free};
    }

    /**
     * Имя устройства: заданное пользователем (Settings.Global device_name, на старых Android -
     * bluetooth_name), а если его нет - модель.
     */
    private String deviceName() {
        String n = null;
        try {
            n = Settings.Global.getString(getContentResolver(), "device_name");
        } catch (Exception ignored) {
        }
        if (n == null || n.trim().length() == 0) {
            try {
                n = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
            } catch (Exception ignored) {
            }
        }
        if (n == null || n.trim().length() == 0) n = Build.MODEL;
        return n == null ? "" : n.trim();
    }

    /** IPv4-адреса устройства, локальные (192.168.x.x и т.п.) первыми. */
    private static java.util.ArrayList<String> localIps() {
        java.util.ArrayList<String> site = new java.util.ArrayList<String>();
        java.util.ArrayList<String> other = new java.util.ArrayList<String>();
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (ad.isLoopbackAddress() || !(ad instanceof Inet4Address)) continue;
                    if (ad.isSiteLocalAddress()) site.add(ad.getHostAddress());
                    else other.add(ad.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        site.addAll(other);
        return site;
    }

    private String batteryJson() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return "null";
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = (level >= 0 && scale > 0) ? level * 100 / scale : -1;
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String pl = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac"
                    : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb"
                    : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "wireless" : "none";
            double temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            return "{\"percent\": " + pct + ", \"charging\": " + charging + ", \"plugged\": \"" + pl
                    + "\", \"temperature_c\": " + temp + "}";
        } catch (Exception e) {
            return "null";
        }
    }

    private String infoJson() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        long ramTotal = 0, ramFree = 0;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramTotal = mi.totalMem;
            ramFree = mi.availMem;
        } catch (Exception ignored) {
        }
        long[] st = storage();
        java.util.ArrayList<String> ips = localIps();
        long now = SystemClock.elapsedRealtime();

        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"device_name\": ").append(jq(deviceName())).append(",\n");
        sb.append("  \"manufacturer\": ").append(jq(String.valueOf(Build.MANUFACTURER))).append(",\n");
        sb.append("  \"model\": ").append(jq(String.valueOf(Build.MODEL))).append(",\n");
        sb.append("  \"android\": ").append(jq(String.valueOf(Build.VERSION.RELEASE))).append(",\n");
        sb.append("  \"sdk\": ").append(Build.VERSION.SDK_INT).append(",\n");
        sb.append("  \"app_version\": ").append(jq(ver == null ? "" : ver)).append(",\n");
        sb.append("  \"time\": ").append(jq(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))).append(",\n");
        sb.append("  \"timezone\": ").append(jq(TimeZone.getDefault().getID())).append(",\n");
        sb.append("  \"uptime_sec\": ").append(now / 1000).append(",\n");
        sb.append("  \"service_uptime_sec\": ").append(serviceStart > 0 ? (now - serviceStart) / 1000 : 0).append(",\n");
        sb.append("  \"canReboot\": ").append(!"none".equals(RebootControl.method(this))).append(",\n");
        sb.append("  \"rebootMethod\": ").append(jq(RebootControl.method(this))).append(",\n");
        String rlog = RebootControl.rootLog;
        if ((rlog == null || rlog.length() == 0) && Build.VERSION.SDK_INT >= 21) rlog = RebootAccessibilityService.lastLog;
        if (rlog != null && rlog.length() > 0) {      // журнал последней попытки перезагрузки через спец. возможности
            sb.append("  \"rebootLog\": ").append(jq(rlog)).append(",\n");
        }
        sb.append("  \"battery\": ").append(batteryJson()).append(",\n");
        sb.append("  \"ip\": ").append(jq(ips.isEmpty() ? "" : ips.get(0))).append(",\n");
        sb.append("  \"ips\": [");
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(jq(ips.get(i)));
        }
        sb.append("],\n");
        sb.append("  \"photo_dir\": ").append(jq(photoDir().getAbsolutePath())).append(",\n");
        String dlog = deleteLog;
        if (dlog != null && dlog.length() > 0) sb.append("  \"deleteLog\": ").append(jq(dlog)).append(",\n");
        sb.append("  \"storage\": {\"total_bytes\": ").append(st[0]).append(", \"free_bytes\": ").append(st[1]).append("},\n");
        sb.append("  \"ram\": {\"total_bytes\": ").append(ramTotal).append(", \"free_bytes\": ").append(ramFree).append("}\n");
        sb.append("}\n");
        return sb.toString();
    }
}
