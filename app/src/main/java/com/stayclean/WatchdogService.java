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
    private static final String CH = "guard";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final FgTracker fg = new FgTracker();
    private long lastQuery;
    static volatile boolean running;
    private static volatile WatchdogService self;

    /** Check right now (the guard just stopped), without restarting the service. */
    static void poke() {
        final WatchdogService w = self;
        if (w != null) w.h.post(new Runnable() { @Override public void run() { w.h.removeCallbacks(w.tick); w.h.post(w.tick); } });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long delay = 10_000;
            try {
                if (Util.phaseNow(WatchdogService.this) != Schedule.Phase.ACTIVE) { stopSelf(); return; }
                if (GuardService.alive) {
                    delay = 60_000;                           // primary is healthy: a rare liveness check
                } else if (Util.blockedNow(WatchdogService.this) || Util.socialNightNow(WatchdogService.this)) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    if (pm.isInteractive()) { fallback(); delay = 800; } else delay = 30_000;
                }
            } catch (Throwable ignored) {}
            h.postDelayed(this, delay);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        self = this;
        goForeground();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (Util.phaseNow(this) != Schedule.Phase.ACTIVE) { stopSelf(); return START_NOT_STICKY; }
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
