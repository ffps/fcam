package com.ffps.fcam;

import java.util.Calendar;

/**
 * Разбор crontab из 5 полей: минута час день месяц день_недели.
 * Поддерживаются *, числа, списки (1,5), диапазоны (1-5), шаги (*&#47;10, 1-30/5, 5/15).
 * День недели: 0-6 (0 - воскресенье), 7 тоже воскресенье.
 * Если день месяца и день недели заданы оба (и ни один не начинается с *), подходит любой из них (как в cron).
 */
public final class Schedule {

    private Schedule() {
    }

    private static final class Spec {
        final boolean[] min = new boolean[60];
        final boolean[] hour = new boolean[24];
        final boolean[] dom = new boolean[32];
        final boolean[] mon = new boolean[13];
        final boolean[] dow = new boolean[7];
        boolean domStar, dowStar;
    }

    /** true, если выражение корректно. */
    public static boolean isValid(String spec) {
        return parse(spec) != null;
    }

    /** Ближайшее время (мс) строго после nowMs, начало минуты; -1, если подходящего времени нет в ближайшие годы. */
    public static long next(String spec, long nowMs) {
        Spec p = parse(spec);
        if (p == null) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1);
        int lastYear = c.get(Calendar.YEAR) + 8;
        for (int guard = 0; guard < 200000; guard++) {
            if (c.get(Calendar.YEAR) > lastYear) return -1;
            if (!p.mon[c.get(Calendar.MONTH) + 1]) {
                c.set(Calendar.DAY_OF_MONTH, 1);
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.MONTH, 1);
                continue;
            }
            if (!dayOk(p, c)) {
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.DAY_OF_MONTH, 1);
                continue;
            }
            if (!p.hour[c.get(Calendar.HOUR_OF_DAY)]) {
                c.set(Calendar.MINUTE, 0);
                c.add(Calendar.HOUR_OF_DAY, 1);
                continue;
            }
            if (!p.min[c.get(Calendar.MINUTE)]) {
                c.add(Calendar.MINUTE, 1);
                continue;
            }
            return c.getTimeInMillis();
        }
        return -1;
    }

    private static boolean dayOk(Spec p, Calendar c) {
        boolean d = p.dom[c.get(Calendar.DAY_OF_MONTH)];
        boolean w = p.dow[c.get(Calendar.DAY_OF_WEEK) - 1];
        if (p.domStar || p.dowStar) return d && w;
        return d || w;
    }

    private static Spec parse(String s) {
        if (s == null) return null;
        String[] f = s.trim().split("\\s+");
        if (f.length != 5) return null;
        Spec p = new Spec();
        if (!field(f[0], p.min, 0, 59, false)) return null;
        if (!field(f[1], p.hour, 0, 23, false)) return null;
        if (!field(f[2], p.dom, 1, 31, false)) return null;
        if (!field(f[3], p.mon, 1, 12, false)) return null;
        if (!field(f[4], p.dow, 0, 7, true)) return null;
        p.domStar = f[2].startsWith("*");
        p.dowStar = f[4].startsWith("*");
        return p;
    }

    private static int num(String s) {
        if (s.length() == 0 || s.length() > 3) return -1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return -1;
        }
        return Integer.parseInt(s);
    }

    /** Заполняет set значениями из одного поля. max - наибольшее допустимое число (для дня недели 7). */
    private static boolean field(String f, boolean[] set, int lo, int max, boolean dow) {
        for (String part : f.split(",", -1)) {
            if (part.length() == 0) return false;
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = num(part.substring(slash + 1));
                if (step < 1) return false;
            }
            int a, b;
            if (range.equals("*")) {
                a = lo;
                b = dow ? 6 : max;
            } else {
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    a = num(range.substring(0, dash));
                    b = num(range.substring(dash + 1));
                } else {
                    a = num(range);
                    b = slash >= 0 ? max : a;
                }
            }
            if (a < lo || b < 0 || b > max || a > b) return false;
            for (int v = a; v <= b; v += step) set[(dow && v == 7) ? 0 : v] = true;
        }
        return true;
    }
}
