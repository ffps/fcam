package com.ffps.fcam;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Запасной способ перезагрузки: открывает системное меню питания и нажимает пункт «Перезагрузка».
 * Содержимое экрана нигде не сохраняется; в журнал попадают только подписи кнопок меню питания
 * (для отладки на незнакомых прошивках, виден в /api/info как rebootLog).
 */
public class RebootAccessibilityService extends AccessibilityService {

    static volatile RebootAccessibilityService instance;
    /** Журнал последней попытки перезагрузки. */
    static volatile String lastLog = "";

    private static final String[] WORDS = {"перезагруз", "перезапуск", "перезапустить", "restart", "reboot"};
    private static final String[] CONFIRM = {"ok", "ок", "да", "yes", "перезагруз", "перезапуск", "restart", "reboot"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private int tries;
    private int clicks;
    private boolean menuSeen;
    private String beforePkg = "";

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

    private void log(String s) {
        if (log.length() < 1800) {
            if (log.length() > 0) log.append(" | ");
            log.append(s);
        }
        lastLog = log.toString();
    }

    private String activePackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            return r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Открывает меню питания и нажимает «Перезагрузка» (и подтверждение, если оно есть). */
    void startReboot() {
        h.removeCallbacksAndMessages(null);
        tries = 0;
        clicks = 0;
        menuSeen = false;
        log.setLength(0);
        beforePkg = activePackage();
        log("start, active=" + beforePkg);
        boolean ok = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        log("power dialog=" + ok);
        h.postDelayed(step, 1200);
    }

    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            tries++;
            if (tries > 14) {
                log("not found, closing the menu");
                closeMenu();
                return;
            }
            boolean clicked;
            try {
                clicked = clickAny(clicks == 0 ? WORDS : CONFIRM);
            } catch (Throwable t) {
                log("error " + t);
                clicked = false;
            }
            if (clicked) {
                clicks++;
                if (clicks >= 2) {
                    h.postDelayed(closeIfStillHere, 8000);
                    return;       // пункт меню и подтверждение нажаты
                }
                h.postDelayed(this, 1500);
            } else {
                h.postDelayed(this, 500);
            }
        }
    };

    /** Если через 8 секунд после нажатия устройство не перезагрузилось (процесс жив), убираем меню с экрана. */
    private final Runnable closeIfStillHere = new Runnable() {
        @Override
        public void run() {
            log("still running, closing the menu");
            closeMenu();
        }
    };

    /** Закрывает системное меню, не трогая приложение на переднем плане. */
    private void closeMenu() {
        try {
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));    // работает до Android 11
        } catch (Throwable ignored) {
        }
        // с Android 12 это широковещание не действует: если меню видели, закрываем кнопкой «Назад»
        if (Build.VERSION.SDK_INT >= 31 && menuSeen) performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private boolean clickAny(String[] words) {
        List<AccessibilityNodeInfo> roots = new ArrayList<AccessibilityNodeInfo>();
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null) {
            roots.add(r);
            CharSequence p = r.getPackageName();
            if (p != null && !p.toString().equals(beforePkg) && !p.toString().equals(getPackageName())) menuSeen = true;
        }
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo n = w.getRoot();
                if (n != null) roots.add(n);
            }
        } catch (Throwable ignored) {
        }
        if (tries == 2 || tries == 7) {      // коротко записываем, что видно на экране
            for (AccessibilityNodeInfo root : roots) {
                StringBuilder sb = new StringBuilder();
                collectTexts(root, sb, 0);
                log("window " + root.getPackageName() + ": " + sb);
            }
        }
        for (AccessibilityNodeInfo root : roots) {
            if (findAndClick(root, words, 0)) return true;
        }
        return false;
    }

    private void collectTexts(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 30 || sb.length() > 200) return;
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        if (t != null && t.length() > 0 && t.length() <= 30) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t);
        }
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), sb, depth + 1);
    }

    private boolean findAndClick(AccessibilityNodeInfo n, String[] words, int depth) {
        if (n == null || depth > 30) return false;
        CharSequence pkg = n.getPackageName();
        if (pkg != null && pkg.toString().equals(getPackageName())) return false;   // не нажимаем в самом fCam
        if (matches(n.getText(), words) || matches(n.getContentDescription(), words)) {
            menuSeen = true;
            if (activate(n)) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (findAndClick(n.getChild(i), words, depth + 1)) return true;
        }
        return false;
    }

    /** Нажимает найденную подпись: сначала по узлу и его родителям, затем касанием по координатам. */
    private boolean activate(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo t = n;
        for (int i = 0; t != null && i < 6; i++) {
            if (t.isEnabled() && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("click ok (" + i + " up)");
                return true;
            }
            t = t.getParent();
        }
        if (Build.VERSION.SDK_INT >= 24) {
            // у некоторых прошивок (например, MIUI) пункты меню не помечены как нажимаемые: касаемся центра пункта
            AccessibilityNodeInfo cell = n.getParent() != null ? n.getParent() : n;
            Rect b = new Rect();
            cell.getBoundsInScreen(b);
            if (b.width() > getResources().getDisplayMetrics().widthPixels / 2) n.getBoundsInScreen(b);
            if (!b.isEmpty()) {
                boolean ok = tap(b.centerX(), b.centerY());
                log("tap " + b.centerX() + "," + b.centerY() + " = " + ok);
                return ok;
            }
        }
        log("click failed");
        return false;
    }

    private boolean tap(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder g = new GestureDescription.Builder();
        g.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        return dispatchGesture(g.build(), null, null);
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
