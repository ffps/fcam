package com.ffps.fcam;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Перезагрузка устройства. Способы по приоритету: владелец устройства (Android 7+), root (su),
 * служба специальных возможностей (Android 5+).
 */
public final class RebootControl {

    private RebootControl() {
    }

    // ---------- владелец устройства ----------

    public static boolean isDeviceOwner(Context c) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- служба специальных возможностей ----------

    /** Служба спец. возможностей сейчас подключена и может нажимать кнопки. */
    public static boolean accessibilityConnected() {
        return Build.VERSION.SDK_INT >= 21 && RebootAccessibilityService.instance != null;
    }

    /** Служба включена в системных настройках (для подписи в интерфейсе). */
    public static boolean accessibilityEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains(c.getPackageName().toLowerCase() + "/");
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- root ----------

    private static final String[] SU_PATHS = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/vendor/bin/su", "/system/sbin/su", "/data/local/xbin/su", "/data/local/bin/su"};

    private static volatile Boolean rootOk = null;      // результат последней проверки (хранится в настройках)
    /** Идёт проверка root: на экране устройства может висеть запрос менеджера прав. */
    public static volatile boolean rootChecking = false;
    /** Что ответил su при последней проверке (для отладки, виден в /api/info как rebootLog). */
    public static volatile String rootLog = "";

    /** Есть ли на устройстве файл su (сам su при этом не запускается, чтобы не вызывать запрос прав). */
    public static boolean suPresent() {
        for (String p : SU_PATHS) {
            if (new File(p).exists()) return true;
        }
        return false;
    }

    /** Root найден и доступ приложению подтверждён проверкой. */
    public static boolean rootGranted(Context c) {
        if (rootOk == null) {
            rootOk = c.getApplicationContext().getSharedPreferences("fcam_root", Context.MODE_PRIVATE).getBoolean("ok", false);
        }
        return rootOk && suPresent();
    }

    /**
     * Проверяет доступ к root командой «su, id» в фоновом потоке. На экране устройства появится запрос менеджера
     * прав (SuperSU, Magisk и т.п.), его нужно подтвердить. done вызывается в основном потоке.
     */
    public static void checkRootAsync(Context c, final Runnable done) {
        if (rootChecking) return;
        rootChecking = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                Process p = null;
                try {
                    p = Runtime.getRuntime().exec("su");
                    final Process pp = p;
                    Thread watchdog = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(45000);      // время на подтверждение запроса
                                try {
                                    pp.exitValue();
                                } catch (IllegalThreadStateException still) {
                                    pp.destroy();
                                }
                            } catch (InterruptedException ignored) {
                            }
                        }
                    });
                    watchdog.setDaemon(true);
                    watchdog.start();
                    OutputStream os = p.getOutputStream();
                    os.write("id\nexit\n".getBytes());
                    os.flush();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append(' ');
                    p.waitFor();
                    String out = sb.toString().trim();
                    ok = out.contains("uid=0");
                    rootLog = "su id: " + (out.length() == 0 ? "(нет ответа: доступ не подтверждён или отклонён)" : out);
                } catch (Exception e) {
                    rootLog = "su error: " + e;
                } finally {
                    if (p != null) {
                        try {
                            p.destroy();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                rootOk = ok;
                app.getSharedPreferences("fcam_root", Context.MODE_PRIVATE).edit().putBoolean("ok", ok).commit();
                rootChecking = false;
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
            }
        }, "fcam-root-check").start();
    }

    private static boolean rootReboot() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            // несколько вариантов команды: какая-то из них есть на любой прошивке; после успешной остальные не выполнятся
            os.write("reboot\n/system/bin/reboot\nsvc power reboot\nexit\n".getBytes());
            os.flush();
            return true;
        } catch (Exception e) {
            rootLog = "su reboot error: " + e;
            return false;
        }
    }

    // ---------- общее ----------

    /** "deviceOwner", "root", "accessibility" или "none". */
    public static String method(Context c) {
        if (isDeviceOwner(c)) return "deviceOwner";
        if (rootGranted(c)) return "root";
        if (accessibilityConnected()) return "accessibility";
        return "none";
    }

    /** Команда, которой приложение назначается владельцем устройства. */
    public static String adbCommand(Context c) {
        return "adb shell dpm set-device-owner " + new ComponentName(c, AdminReceiver.class).flattenToShortString();
    }

    /** Запускает перезагрузку; false, если прав нет. */
    public static boolean perform(Context c) {
        if (isDeviceOwner(c)) {
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
                dpm.reboot(new ComponentName(c, AdminReceiver.class));
                return true;
            } catch (Throwable t) {
                // пробуем следующий способ
            }
        }
        if (rootGranted(c) && rootReboot()) return true;
        RebootAccessibilityService s = Build.VERSION.SDK_INT >= 21 ? RebootAccessibilityService.instance : null;
        if (s != null) {
            s.startReboot();
            return true;
        }
        return false;
    }

    /** Снимает права владельца устройства (иначе приложение нельзя удалить). */
    public static boolean clearOwner(Context c) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.clearDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
        return !isDeviceOwner(c);
    }
}
