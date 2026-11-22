package com.stayclean;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Second line of defence. Always running (foreground service), re-armed by alarms and boot.
 * If the accessibility guard is not alive at night, it takes over using Usage Access + an overlay.
 */
public class WatchdogService extends Service {
    private static final String CH = "guard", CH_RESUME = "resume";
    static final String ACTION_BANK_WATCH = "com.stayclean.BANK_WATCH";
    private static final int N_RESUME = 8;
    private boolean resumeShown;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final FgTracker fg = new FgTracker();
    private long lastQuery;
    static volatile boolean running;
    private static volatile WatchdogService self;

    /** Cancel the "switch me back on" prompt (the guard is alive again). */
    static void cancelResume(Context c) {
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(N_RESUME); } catch (Throwable ignored) {}
    }

    /** Check right now (the guard just stopped), without restarting the service. */
    static void poke() {
        final WatchdogService w = self;
        if (w != null) w.h.post(new Runnable() { @Override public void run() { w.h.removeCallbacks(w.tick); w.h.post(w.tick); } });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long delay = 10_000;
            try {
                boolean paused = BankGuard.isPaused(WatchdogService.this);
                Schedule.Phase ph = Util.phaseNow(WatchdogService.this);
                if (ph != Schedule.Phase.ACTIVE && !paused) { stopSelf(); return; }

                if (paused) delay = bankWatch();

                if (ph == Schedule.Phase.ACTIVE && GuardService.alive && !paused) {
                    delay = 60_000;                           // primary is healthy: a rare liveness check
                } else if (ph == Schedule.Phase.ACTIVE && !GuardService.alive
                        && (Util.blockedNow(WatchdogService.this) || Util.socialNightNow(WatchdogService.this))) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm.isInteractive()) { fallback(); delay = Math.min(delay, 800); } else delay = Math.min(delay, 30_000);
                }
            } catch (Throwable ignored) {}
            h.postDelayed(this, delay);
        }
    };

    /** While Bank Pass has the guard switched off: notice when you leave the bank app, then prompt to resume. */
    private long bankWatch() {
        try {
            updateForeground();
            String cur = fg.current();
            String bank = BankGuard.pausedFor(WatchdogService.this);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean screenOff = pm != null && !pm.isInteractive();
            boolean leftBank = cur != null && !cur.isEmpty() && !cur.equals(bank) && !cur.equals(getPackageName());
            boolean timeout = System.currentTimeMillis() - BankGuard.pausedSince(WatchdogService.this) > 10 * 60_000L;
            if (!resumeShown && (leftBank || screenOff || timeout)) { showResume(); resumeShown = true; }
        } catch (Throwable ignored) {}
        return resumeShown ? 5_000 : 1_500;
    }

    private void showResume() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(CH_RESUME, "Resume protection", NotificationManager.IMPORTANCE_HIGH));
        Intent s = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String comp = getPackageName() + "/" + GuardService.class.getName();   // scroll to & highlight Stay Clean (AOSP/Samsung)
        android.os.Bundle args = new android.os.Bundle();
        args.putString(":settings:fragment_args_key", comp);
        s.putExtra(":settings:fragment_args_key", comp);
        s.putExtra(":settings:show_fragment_args", args);
        PendingIntent pi = PendingIntent.getActivity(this, 1, s, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH_RESUME)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Protection is paused")
                .setContentText("Tap to switch Stay Clean back on.")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        nm.notify(N_RESUME, n);
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        self = this;
        goForeground();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (Util.phaseNow(this) != Schedule.Phase.ACTIVE && !BankGuard.isPaused(this)) { stopSelf(); return START_NOT_STICKY; }
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Guard", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Stay Clean")
                .setContentText("On duty until 5 am IST")
                .setOngoing(true)
                .setContentIntent(open)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
    }

    private void fallback() {
        boolean usage = hasUsageAccess();
        boolean overlay = Settings.canDrawOverlays(this);
        Targets.Enforcer e = Targets.chooseEnforcer(false, usage, overlay);
        if (e == Targets.Enforcer.NONE) return;
        updateForeground();
        final String pkg = fg.current();
        boolean food = Targets.isBlockedApp(pkg) && Util.blockedNow(this);
        boolean social = Targets.isSocialApp(pkg) && Util.socialNightNow(this);
        if (!food && !social) return;
        if (e == Targets.Enforcer.FALLBACK_OVERLAY) {
            Overlay.show(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, pkg, new Runnable() {
                @Override public void run() { goHome(); }
            }, social ? Phrases.SOCIAL.next() : null);
        } else {
            goHome();
            Overlay.killApp(this, pkg);
        }
    }

    private void updateForeground() {
        UsageStatsManager u = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        long from = lastQuery == 0 ? now - 2 * 3_600_000L : Math.min(lastQuery, now) - 1000;
        UsageEvents ev = u.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            fg.onEvent(e.getPackageName(), e.getEventType());
        }
        lastQuery = now;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager a = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }

    private void goHome() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {}
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (Util.phaseNow(this) == Schedule.Phase.ACTIVE) Util.armAlarm(this, 1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        self = null;
        h.removeCallbacks(tick);
        if (Util.phaseNow(this) == Schedule.Phase.ACTIVE) Util.armAlarm(this, 1500);   // died early: come back
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
