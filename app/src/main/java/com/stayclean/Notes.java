package com.stayclean;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;

/** Gentle notifications: wind-down before 11 pm, the emergency last minute. One quiet channel, one tone. */
final class Notes {
    private Notes() {}

    static final String CH = "gentle";
    static final int N_WIND = 21, N_EMG = 22;

    static void channel(Context c) {
        NotificationChannel ch = new NotificationChannel(CH, "Gentle reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setSound(null, (AudioAttributes) null);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 35, 90, 35});
        ch.setShowBadge(false);
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
    }

    private static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void post(Context c, int id, String title, String text, long timeoutMs) {
        try {
            channel(c);
            Notification.Builder b = new Notification.Builder(c, CH)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setColor(0xFFE8A15A)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c));
            if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs);
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, b.build());
        } catch (Throwable ignored) {}
    }

    static void windDown(Context c, int minutes) {
        if (minutes >= 10)
            post(c, N_WIND, "10 minutes to quiet hours", "Start wrapping up on Instagram and X. They rest at 11.", 10 * 60_000L);
        else
            post(c, N_WIND, "5 minutes left", "Say your goodnights. Instagram and X rest at 11.", 5 * 60_000L);
    }

    static void oneMinuteLeft(Context c) {
        post(c, N_EMG, "One minute of emergency time left", "Finish what you need. It closes for the night after this.", 2 * 60_000L);
    }

    // ---------- wind-down alarms: 10:50 pm and 10:55 pm IST ----------
    static final int[] WIND_MIN = {22 * 60 + 50, 22 * 60 + 55};

    /** Next reminder after now: {epoch ms, minutes before 11}. Pure (unit-tested). */
    static long[] nextWindDown(long now) {
        long day = Morning.dayOf(now);
        for (int d = 0; d <= 1; d++) {
            for (int m : WIND_MIN) {
                long at = (day + d) * 86_400_000L - Schedule.IST_OFFSET_MS + m * 60_000L;
                if (at > now) return new long[]{at, 23 * 60 - m};
            }
        }
        return new long[]{now + 86_400_000L, 10};
    }

    static final String ACTION_WIND = "com.stayclean.WIND_DOWN";

    /** Arm the next wind-down reminder (or clear it when the night lock is off). Cheap; call it often. */
    static void armWindDown(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(c, WindDownReceiver.class).setAction(ACTION_WIND);
            long[] nx = nextWindDown(System.currentTimeMillis());
            i.putExtra("at", nx[0]).putExtra("min", (int) nx[1]);
            PendingIntent pi = PendingIntent.getBroadcast(c, 4, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (!Util.socialOn(c)) { am.cancel(pi); return; }
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nx[0], pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nx[0], pi);
        } catch (Throwable ignored) {}
    }
}
