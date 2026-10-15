package com.ffps.fcam;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Запасной способ перезагрузки: открывает системное меню питания и нажимает пункт «Перезагрузка».
 * События окон не читаются и нигде не сохраняются, служба смотрит экран только во время перезагрузки.
 */
public class RebootAccessibilityService extends AccessibilityService {

    static volatile RebootAccessibilityService instance;

    private static final String[] WORDS = {"перезагруз", "перезапуск", "перезапустить", "restart", "reboot"};
    private static final String[] CONFIRM = {"ok", "ок", "да", "yes", "перезагруз", "перезапуск", "restart", "reboot"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private int tries;
    private int clicks;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // события не нужны
    }

    @Override
    public void onInterrupt() {
    }

    /** Открывает меню питания и нажимает «Перезагрузка» (и подтверждение, если оно есть). */
    void startReboot() {
        h.removeCallbacksAndMessages(null);
        tries = 0;
        clicks = 0;
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        h.postDelayed(step, 1200);
    }

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            tries++;
            if (tries > 14) return;
            boolean clicked;
            try {
                clicked = clickAny(clicks == 0 ? WORDS : CONFIRM);
            } catch (Throwable t) {
                clicked = false;
            }
            if (clicked) {
                clicks++;
                if (clicks >= 2) return;       // пункт меню и, возможно, подтверждение
                h.postDelayed(this, 1500);
            } else {
                h.postDelayed(this, 500);
            }
        }
    };

    private boolean clickAny(String[] words) {
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) roots.add(r);
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo n = w.getRoot();
                if (n != null) roots.add(n);
            }
        } catch (Throwable ignored) {
        }
        for (AccessibilityNodeInfo root : roots) {
            if (findAndClick(root, words, 0)) return true;
        }
        return false;
    }

    private boolean findAndClick(AccessibilityNodeInfo n, String[] words, int depth) {
        if (n == null || depth > 30) return false;
        CharSequence pkg = n.getPackageName();
        if (pkg != null && pkg.toString().equals(getPackageName())) return false;   // не нажимаем в самом fCam
        if (matches(n.getText(), words) || matches(n.getContentDescription(), words)) {
            AccessibilityNodeInfo t = n;
            while (t != null && !t.isClickable()) t = t.getParent();
            if (t != null && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (findAndClick(n.getChild(i), words, depth + 1)) return true;
        }
        return false;
    }

    private static boolean matches(CharSequence cs, String[] words) {
        if (cs == null) return false;
        String s = cs.toString().trim().toLowerCase();
        if (s.length() == 0 || s.length() > 40) return false;      // подписи кнопок короткие
        for (String w : words) {
            if (w.length() <= 3 ? s.equals(w) : s.contains(w)) return true;
        }
        return false;
    }
}
