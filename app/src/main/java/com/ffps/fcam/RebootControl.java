package com.ffps.fcam;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** Перезагрузка устройства без root: сначала Device Owner (Android 7+), затем служба спец. возможностей (Android 5+). */
public final class RebootControl {

    private RebootControl() {
    }

    public static boolean isDeviceOwner(Context c) {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

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

    /** "deviceOwner", "accessibility" или "none". */
    public static String method(Context c) {
        if (isDeviceOwner(c)) return "deviceOwner";
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
                // пробуем запасной способ
            }
        }
        RebootAccessibilityService s = RebootAccessibilityService.instance;
        if (Build.VERSION.SDK_INT >= 21 && s != null) {
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
