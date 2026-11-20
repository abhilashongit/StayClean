package com.stayclean;

/**
 * "5 minutes per 30-minute interval" meter (used for Instagram).
 * The interval starts the first time the app comes to the foreground after the previous interval ended.
 * Foreground time inside the interval is added up; at 5 minutes the app is locked until the interval is over.
 */
public final class UsageMeter {
    public static final long INTERVAL_MS = 30 * 60_000L;
    public static final long ALLOW_MS = 5 * 60_000L;
    public static final long MAX_CREDIT_GAP_MS = 5_000L;     // never credit more than this between two samples

    public enum Verdict { ALLOW, BLOCK }

    private long start = -1, used = 0, last = -1;
    private long allow = ALLOW_MS;                           // per-interval allowance, 0 to 7:30, set by the user

    public UsageMeter() {}

    public synchronized void setAllowance(long ms) { allow = Math.max(0, ms); }
    public synchronized long allowance() { return allow; }
    public UsageMeter(long start, long used) { this.start = start; this.used = Math.max(0, used); }

    /** Feed one observation. fg = the app is on screen right now (and the screen is on). */
    public synchronized Verdict sample(long now, boolean fg) {
        if (start >= 0 && now - start >= INTERVAL_MS) { start = -1; used = 0; last = -1; }   // new interval
        if (!fg) {
            if (last >= 0 && start >= 0) used += credit(now - last);
            last = -1;
            return used >= allow ? Verdict.BLOCK : Verdict.ALLOW;
        }
        if (start < 0) { start = now; used = 0; last = now; return allow <= 0 ? Verdict.BLOCK : Verdict.ALLOW; }
        if (used >= allow) { last = -1; return Verdict.BLOCK; }
        if (last >= 0) used += credit(now - last);
        last = now;
        if (used >= allow) { last = -1; return Verdict.BLOCK; }
        return Verdict.ALLOW;
    }

    private static long credit(long gap) { return Math.max(0, Math.min(gap, MAX_CREDIT_GAP_MS)); }

    public synchronized long lockoutMs(long now) {
        return start < 0 ? 0 : Math.max(0, start + INTERVAL_MS - now);
    }

    /** Whole minutes left in the lockout, rounded up (for "Back in N min."). */
    public synchronized int lockoutMinutes(long now) { return (int) Math.max(1, (lockoutMs(now) + 59_999L) / 60_000L); }

    /** Allowance left in the current interval. */
    public synchronized long remainingMs() { return Math.max(0, allow - used); }

    public synchronized long start() { return start; }
    public synchronized long used() { return used; }
}
