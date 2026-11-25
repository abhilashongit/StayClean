package com.stayclean;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Emergency access: 5 minutes for the whole night (11 pm to 5 am), shared by Instagram and X, app and website.
 * You start it on purpose in Stay Clean; time counts only while Instagram or X is actually on screen.
 */
final class Emergency {
    private Emergency() {}

    static final long POOL_MS = 5 * 60_000L;
    private static final long CAP_MS = 6_000L;             // never credit more than this between two looks

    /** Night id: 11 pm on day D through 5 am on D+1 all map to D. */
    static long nightOf(long epochMs) { return Morning.dayOf(epochMs - 6 * 3_600_000L); }

    /** Pure counter (unit-tested). */
    static final class Pool {
        long night = Long.MIN_VALUE, used;
        boolean on;
        private long last = -1;

        void rollTo(long night) { if (this.night != night) { this.night = night; used = 0; on = false; last = -1; } }
        long remaining() { return Math.max(0, POOL_MS - used); }

        /** fg = Instagram or X is on screen right now. Returns time left. */
        long sample(long now, boolean fg) {
            if (last >= 0 && now > last) used = Math.min(POOL_MS, used + Math.min(now - last, CAP_MS));
            last = fg && on && used < POOL_MS ? now : -1;
            return remaining();
        }
    }

    private static Pool pool;
    private static boolean warned;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("emg", Context.MODE_PRIVATE); }

    private static synchronized Pool pool(Context c, long now) {
        if (pool == null) {
            SharedPreferences p = sp(c);
            pool = new Pool();
            pool.night = p.getLong("night", Long.MIN_VALUE);
            pool.used = p.getLong("used", 0);
            pool.on = p.getBoolean("on", false);
        }
        long n = nightOf(now);
        if (pool.night != n) { pool.rollTo(n); warned = false; save(c); }
        return pool;
    }

    private static void save(Context c) {
        sp(c).edit().putLong("night", pool.night).putLong("used", pool.used).putBoolean("on", pool.on).apply();
    }

    /** May emergency access start now? Only inside the night lock, and only while time is left. */
    static synchronized boolean canStart(Context c) {
        long now = System.currentTimeMillis();
        return Util.socialNightNow(c) && pool(c, now).remaining() > 0;
    }

    static synchronized boolean isOn(Context c) { long now = System.currentTimeMillis(); return pool(c, now).on && pool.remaining() > 0; }

    static synchronized void start(Context c) {
        if (!canStart(c)) return;
        pool.on = true;
        save(c);
    }

    static synchronized long remainingMs(Context c) { return pool(c, System.currentTimeMillis()).remaining(); }

    /** True while emergency access is running tonight and time is left: Instagram and X may open. */
    static synchronized boolean allowsNow(Context c) { return Util.socialNightNow(c) && isOn(c); }

    /** Count one look at Instagram/X (fg) or note that it is gone (!fg). Returns time left. */
    static synchronized long sample(Context c, boolean fg) {
        long now = System.currentTimeMillis();
        Pool p = pool(c, now);
        long before = p.remaining();
        long left = p.sample(now, fg);
        if (left != before) save(c);
        if (!warned && left > 0 && left <= 60_000L && before > 60_000L) { warned = true; Notes.oneMinuteLeft(c); }
        return left;
    }

    static synchronized void idle(Context c) { if (pool != null && pool.on) sample(c, false); }

    static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
