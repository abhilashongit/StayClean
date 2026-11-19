package com.stayclean;

/** Pure time logic. IST is a fixed +05:30 offset (no DST, ever), independent of the phone's time zone. */
public final class Schedule {
    private Schedule() {}

    public static final long IST_OFFSET_MS = 19_800_000L;      // +05:30
    public static final int BLOCK_START_HOUR = 0;               // 12:00 am IST
    public static final int BLOCK_END_HOUR = 5;                 // 5:00 am IST (exclusive)
    public static final int TAMPER_START_HOUR = 23;             // settings-lock starts 11 pm IST
    public static final int TAMPER_END_HOUR = 6;                // and ends 6 am IST (exclusive)

    public static int istHour(long epochMs) {
        long h = Math.floorDiv(epochMs + IST_OFFSET_MS, 3_600_000L);
        return (int) Math.floorMod(h, 24L);
    }

    /** True from 00:00:00.000 up to (not including) 05:00:00.000 IST. */
    public static boolean inWindow(long epochMs) {
        int h = istHour(epochMs);
        return h >= BLOCK_START_HOUR && h < BLOCK_END_HOUR;
    }

    /** Wider window in which settings pages that can disable this app are blocked. */
    public static boolean tamperLock(long epochMs) {
        int h = istHour(epochMs);
        return h >= TAMPER_START_HOUR || h < TAMPER_END_HOUR;
    }

    /** Milliseconds until the next 12:00 am IST (0 if already inside the window). */
    public static long msUntilWindow(long epochMs) {
        if (inWindow(epochMs)) return 0;
        long intoDay = Math.floorMod(epochMs + IST_OFFSET_MS, 86_400_000L);
        return 86_400_000L - intoDay;
    }

    /** DAY: nothing runs. TAMPER: settings-lock only. ACTIVE: full guard (service + watchdog). */
    public enum Phase { DAY, TAMPER, ACTIVE }

    public static final long SLACK_MS = 300;
    public static final long ACTIVE_KEEPALIVE_MS = 5 * 60_000L;
    /** Phase boundaries as IST minute-of-day: 00:00, 05:00, 05:05, 06:00, 22:55. */
    private static final int[] BOUNDARIES_MIN = {0, 300, 305, 360, 1375};

    /** Instagram + X night lock: 23:00 to 05:00 IST. */
    public static boolean socialWindow(long epochMs) {
        int h = istHour(epochMs);
        return h >= 23 || h < 5;
    }

    /** The night-lock toggle may only be switched off outside the tamper-lock hours (6 am to 11 pm). */
    public static boolean canDisableToggle(long epochMs) { return !tamperLock(epochMs); }

    /** ACTIVE 22:55 to 05:05, TAMPER 05:05 to 06:00 (tail of the settings lock), otherwise DAY. */
    public static Phase phase(long epochMs) {
        int m = (int) (Math.floorMod(epochMs + IST_OFFSET_MS, 86_400_000L) / 60_000L);
        if (m >= 22 * 60 + 55 || m < 5 * 60 + 5) return Phase.ACTIVE;
        if (m < 6 * 60) return Phase.TAMPER;
        return Phase.DAY;
    }

    public static long msToNextBoundary(long epochMs) {
        long d = Math.floorMod(epochMs + IST_OFFSET_MS, 86_400_000L);
        long best = Long.MAX_VALUE;
        for (int b : BOUNDARIES_MIN) {
            long t = b * 60_000L;
            long delta = t > d ? t - d : t + 86_400_000L - d;
            if (delta < best) best = delta;
        }
        return best;
    }

    /** One alarm at a time: lands just after the next boundary; during ACTIVE also a 5-minute keep-alive. */
    public static long nextAlarmDelayMs(long epochMs) {
        long d = msToNextBoundary(epochMs) + SLACK_MS;
        if (phase(epochMs) == Phase.ACTIVE) d = Math.min(d, ACTIVE_KEEPALIVE_MS);
        return Math.max(1000, d);
    }
}
