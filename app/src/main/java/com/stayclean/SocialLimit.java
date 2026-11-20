package com.stayclean;

/** The Instagram time allowance per 30 minutes: 0 to 7:30 in 30-second steps. One change a day; raising it never at night. */
public final class SocialLimit {
    private SocialLimit() {}

    public static final long STEP_MS = 30_000L;
    public static final long MAX_MS = 450_000L;          // 7 min 30 s
    public static final long DEFAULT_MS = UsageMeter.ALLOW_MS;

    public enum Verdict { OK, SAME, ALREADY_TODAY, RAISE_AT_NIGHT }

    /** Snaps to the nearest 30 seconds and keeps it inside 0 to 7:30. */
    public static long clamp(long ms) {
        long snapped = Math.round(ms / (double) STEP_MS) * STEP_MS;
        return Math.max(0, Math.min(MAX_MS, snapped));
    }

    /**
     * May the limit change from cur to next? lastChangeDay is the IST day index of the previous change (or any
     * value that matches no day). Both clocks are checked, so winding the phone clock back never buys a second change.
     * nightLock = the 11 pm to 6 am settings lock is on (either clock).
     */
    public static Verdict check(long cur, long next, long lastChangeDay, long dayBySystem, long dayByTrusted, boolean nightLock) {
        if (clamp(next) == cur) return Verdict.SAME;
        if (lastChangeDay == dayBySystem || lastChangeDay == dayByTrusted) return Verdict.ALREADY_TODAY;
        if (clamp(next) > cur && nightLock) return Verdict.RAISE_AT_NIGHT;
        return Verdict.OK;
    }

    /** "5 minutes", "30 seconds", "7 min 30 sec", or "0, always closed". */
    public static String label(long ms) {
        long m = ms / 60_000L, s = (ms % 60_000L) / 1000L;
        if (ms <= 0) return "0 minutes, always closed";
        if (s == 0) return m + (m == 1 ? " minute" : " minutes");
        if (m == 0) return s + " seconds";
        return m + " min " + s + " sec";
    }
}
