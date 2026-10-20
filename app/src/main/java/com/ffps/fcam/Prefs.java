package com.ffps.fcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Camera;

public class Prefs {
    public boolean enabled = false;
    public boolean autostart = false;
    public int camera = 0;         // при первом запуске берётся фронтальная камера (см. load)
    public float threshold = 10f;  // % изменившихся точек
    public int minGap = 30;        // сек между началами серий
    public int count = 2;          // кадров в серии
    public float interval = 1f;    // сек между кадрами
    public String rebootCron = "";   // crontab автоперезагрузки (пусто - выключена)
    public int keepDays = 0;       // хранить фото, дней (0 - не удалять)
    public int port = 8080;
    public String password = "";

    private static int defaultCamera = -2;

    /** Индекс первой фронтальной камеры (если её нет - 0). Определяется один раз. */
    private static int frontCameraIndex() {
        if (defaultCamera != -2) return defaultCamera;
        int r = 0;
        try {
            int n = Camera.getNumberOfCameras();
            Camera.CameraInfo ci = new Camera.CameraInfo();
            for (int i = 0; i < n; i++) {
                Camera.getCameraInfo(i, ci);
                if (ci.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    r = i;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        defaultCamera = r;
        return r;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("fcam", Context.MODE_PRIVATE);
    }

    public static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.enabled = s.getBoolean("enabled", p.enabled);
        p.autostart = s.getBoolean("autostart", p.autostart);
        // пока камера не выбрана и не сохранена, по умолчанию фронтальная
        p.camera = s.contains("camera") ? s.getInt("camera", p.camera) : frontCameraIndex();
        p.threshold = s.getFloat("threshold", p.threshold);
        p.minGap = s.getInt("minGap", p.minGap);
        p.count = s.getInt("count", p.count);
        p.interval = s.getFloat("interval", p.interval);
        p.keepDays = s.getInt("keepDays", p.keepDays);
        p.rebootCron = s.getString("rebootCron", p.rebootCron);
        p.port = s.getInt("port", p.port);
        p.password = s.getString("password", p.password);
        return p;
    }

    /** Проверка значений; null, если всё корректно. */
    public String error() {
        if (!(threshold > 0)) return "threshold должен быть больше 0";
        if (minGap < 0) return "min_gap не может быть отрицательным";
        if (count < 1) return "count должен быть не меньше 1";
        if (!(interval >= 0)) return "interval не может быть отрицательным";
        if (keepDays < 0) return "keep_days не может быть отрицательным";
        if (port < 1024 || port > 65535) return "port должен быть от 1024 до 65535";
        if (camera < 0) return "camera не может быть отрицательным";
        if (rebootCron != null && rebootCron.trim().length() > 0 && !Schedule.isValid(rebootCron))
            return "reboot_cron: неверное выражение crontab (нужно 5 полей: минута час день месяц день_недели)";
        return null;
    }

    public void save(Context c) {
        sp(c).edit()
                .putBoolean("enabled", enabled)
                .putBoolean("autostart", autostart)
                .putInt("camera", camera)
                .putFloat("threshold", threshold)
                .putInt("minGap", minGap)
                .putInt("count", count)
                .putFloat("interval", interval)
                .putInt("keepDays", keepDays)
                .putString("rebootCron", rebootCron == null ? "" : rebootCron)
                .putInt("port", port)
                .putString("password", password)
                .commit();
    }
}
