package com.ffps.fcam;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    public boolean enabled = false;
    public boolean autostart = false;
    public int camera = 0;
    public float threshold = 2f;   // % изменившихся точек
    public int minGap = 60;        // сек между началами серий
    public int count = 3;          // кадров в серии
    public float interval = 1f;    // сек между кадрами
    public int port = 8081;
    public String password = "";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("fcam", Context.MODE_PRIVATE);
    }

    public static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.enabled = s.getBoolean("enabled", p.enabled);
        p.autostart = s.getBoolean("autostart", p.autostart);
        p.camera = s.getInt("camera", p.camera);
        p.threshold = s.getFloat("threshold", p.threshold);
        p.minGap = s.getInt("minGap", p.minGap);
        p.count = s.getInt("count", p.count);
        p.interval = s.getFloat("interval", p.interval);
        p.port = s.getInt("port", p.port);
        p.password = s.getString("password", p.password);
        return p;
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
                .putInt("port", port)
                .putString("password", password)
                .commit();
    }
}
