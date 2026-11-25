package com.stayclean;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class Util {
    private Util() {}

    static final String ACTION_KEEPALIVE = "com.stayclean.KEEPALIVE";
    private static final long RESYNC_MS = 12 * 3_600_000L;   // one network check a night is plenty
    private static final AtomicBoolean syncing = new AtomicBoolean(false);
    private static volatile long lastSyncElapsed = -1;
    private static TrustedClock CLOCK;
    private static long BOOT = -1;

    /** In-memory clock (loaded from disk once), so per-event checks are plain arithmetic. */
    static synchronized TrustedClock clock(Context c) {
        if (CLOCK == null) {
            final SharedPreferences sp = c.getApplicationContext().getSharedPreferences("clock", Context.MODE_PRIVATE);
            final Map<String, Long> mem = new HashMap<>();
            for (String k : new String[]{"a_wall", "a_elapsed", "a_boot"}) if (sp.contains(k)) mem.put(k, sp.getLong(k, 0));
            CLOCK = new TrustedClock(new TrustedClock.Store() {
                @Override public synchronized long get(String k, long d) { Long v = mem.get(k); return v == null ? d : v; }
                @Override public synchronized void put(String k, long v) { mem.put(k, v); sp.edit().putLong(k, v).apply(); }
            });
        }
        return CLOCK;
    }

    static synchronized long bootId(Context c) {
        if (BOOT < 0) {
            try { BOOT = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, 0); }
            catch (Throwable t) { BOOT = 0; }
        }
        return BOOT;
    }

    static boolean blockedNow(Context c) {
        return clock(c).blockedNow(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId(c));
    }

    static boolean tamperLockNow(Context c) {
        return clock(c).tamperLockNow(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId(c));
    }

    // ---- night-lock toggle (Instagram + X), default ON ----
    private static volatile int SOCIAL = -1;

    static boolean socialOn(Context c) {
        if (SOCIAL < 0) SOCIAL = c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("social_on", true) ? 1 : 0;
        return SOCIAL == 1;
    }

    static void setSocialOn(Context c, boolean on) {
        SOCIAL = on ? 1 : 0;
        c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putBoolean("social_on", on).apply();
        Notes.armWindDown(c);
    }

    /** Toggle on AND inside 23:00 to 05:00 (stricter of the two clocks). */
    static boolean socialNightNow(Context c) {
        return socialOn(c) && clock(c).socialWindowNow(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId(c));
    }

    /** The toggle may only be switched OFF between 6 am and 11 pm. */
    static boolean canDisableSocial(Context c) { return !tamperLockNow(c); }

    // ---- Instagram and X meters: separate allowances per 30 minutes, app and website share one per service ----
    private static final java.util.EnumMap<Social.Svc, UsageMeter> METERS = new java.util.EnumMap<>(Social.Svc.class);

    static synchronized UsageMeter meter(Context c, Social.Svc svc) {
        UsageMeter m = METERS.get(svc);
        if (m == null) {
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(svc.meterPrefs, Context.MODE_PRIVATE);
            m = new UsageMeter(sp.getLong("start", -1), sp.getLong("used", 0));
            m.setAllowance(socialLimitMs(c, svc));
            METERS.put(svc, m);
        }
        return m;
    }

    static void saveMeter(Context c, Social.Svc svc) {
        UsageMeter m = meter(c, svc);
        c.getApplicationContext().getSharedPreferences(svc.meterPrefs, Context.MODE_PRIVATE).edit()
                .putLong("start", m.start()).putLong("used", m.used()).apply();
    }

    // ---- allowance per 30 minutes: 0 to 7:30, one change a day, per service ----
    private static SharedPreferences cfg(Context c) { return c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    static long socialLimitMs(Context c, Social.Svc svc) {
        return SocialLimit.clamp(cfg(c).getLong(svc.limitKey, SocialLimit.DEFAULT_MS));
    }

    private static long dayBySystem() { return Morning.dayOf(System.currentTimeMillis()); }
    private static long dayByTrusted(Context c) {
        long t = clock(c).trustedNow(SystemClock.elapsedRealtime(), bootId(c));
        return t >= 0 ? Morning.dayOf(t) : dayBySystem();
    }

    static SocialLimit.Verdict checkSocialLimit(Context c, Social.Svc svc, long next) {
        long last = cfg(c).getLong(svc.dayKey, Long.MIN_VALUE);
        return SocialLimit.check(socialLimitMs(c, svc), next, last, dayBySystem(), dayByTrusted(c), tamperLockNow(c));
    }

    static boolean socialLimitChangedToday(Context c, Social.Svc svc) {
        long last = cfg(c).getLong(svc.dayKey, Long.MIN_VALUE);
        return last == dayBySystem() || last == dayByTrusted(c);
    }

    static void setSocialLimit(Context c, Social.Svc svc, long ms) {
        long v = SocialLimit.clamp(ms);
        cfg(c).edit().putLong(svc.limitKey, v).putLong(svc.dayKey, Math.max(dayBySystem(), dayByTrusted(c))).apply();
        meter(c, svc).setAllowance(v);
    }

    /** Real time if we have an anchor for this boot, else the system clock. */
    static long meterNow(Context c) {
        long t = clock(c).trustedNow(SystemClock.elapsedRealtime(), bootId(c));
        return t >= 0 ? t : System.currentTimeMillis();
    }

    static Schedule.Phase phaseNow(Context c) {
        return clock(c).phaseNow(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId(c));
    }

    /**
     * The one place that moves the app between DAY / TAMPER / ACTIVE:
     * ACTIVE starts the watchdog; otherwise it is stopped. The accessibility guard's event filter is swapped.
     * Always re-arms exactly one alarm for the next boundary.
     */
    static void applyPhase(Context c) {
        Context app = c.getApplicationContext();
        Schedule.Phase p = phaseNow(app);
        if (p == Schedule.Phase.ACTIVE) startWatchdog(app); else stopWatchdog(app);
        GuardService g = GuardService.instance;
        if (g != null) g.applyPhase(p);
        if (p != Schedule.Phase.DAY) syncIfStale(app);
        armAlarm(app, Schedule.nextAlarmDelayMs(System.currentTimeMillis()));
    }

    /** Re-anchor to real network time (Date header) on a background thread. */
    static void syncNow(final Context c) {
        if (!syncing.compareAndSet(false, true)) return;
        try {
            String[] urls = {"https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace"};
            for (String u : urls) {
                HttpURLConnection h = null;
                try {
                    h = (HttpURLConnection) new URL(u).openConnection();
                    h.setRequestMethod("HEAD");
                    h.setConnectTimeout(6000);
                    h.setReadTimeout(6000);
                    h.getResponseCode();
                    long date = h.getDate();
                    long elapsed = SystemClock.elapsedRealtime();
                    if (date > 0) {
                        clock(c).setAnchor(date, elapsed, bootId(c));
                        lastSyncElapsed = elapsed;
                        return;
                    }
                } catch (Throwable ignored) {
                } finally { if (h != null) h.disconnect(); }
            }
        } finally { syncing.set(false); }
    }

    static void syncIfStale(final Context c) {
        long now = SystemClock.elapsedRealtime();
        boolean stale = lastSyncElapsed < 0 || now - lastSyncElapsed > RESYNC_MS
                || clock(c).trustedNow(now, bootId(c)) < 0;
        if (stale && !syncing.get()) {
            final Context app = c.getApplicationContext();
            new Thread(new Runnable() { @Override public void run() { syncNow(app); } }, "clock-sync").start();
        }
    }

    static boolean accessibilityEnabled(Context c) {
        try {
            String flat = new ComponentName(c, GuardService.class).flattenToString();
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            for (String part : s.split(":")) if (part.equalsIgnoreCase(flat)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    static void startWatchdog(Context c) {
        if (WatchdogService.running) { WatchdogService.poke(); return; }   // already on duty: no restart, no re-post
        Intent i = new Intent(c, WatchdogService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable t) {
            armAlarm(c, 3000);   // exact alarms are allowed to start foreground services
        }
    }

    static void stopWatchdog(Context c) {
        try { c.stopService(new Intent(c, WatchdogService.class)); } catch (Throwable ignored) {}
    }

    static void armAlarm(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, KeepAliveReceiver.class).setAction(ACTION_KEEPALIVE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + Math.max(1000, delayMs);
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {}
    }
}
