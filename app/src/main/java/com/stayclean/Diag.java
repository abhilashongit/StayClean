package com.stayclean;

/** In-memory QA counters shown at the bottom of the app. Never written to disk, never holds a web address. */
public final class Diag {
    private Diag() {}
    public static volatile String browserPkg;
    public static volatile long lastReadAt;
    public static volatile int reads, misses;
    public static volatile String lastAction = "";
    /** Reddit app: screens looked at, community pages recognised, adult communities closed. Counts only, never names. */
    public static volatile int redditScans, redditPages, redditClosed;
    /** Morning glow: times it has shown since the app process started. */
    public static volatile int morningShown;

    public static void onBrowserRead(String pkg, boolean ok) {
        browserPkg = pkg;
        if (ok) { reads++; lastReadAt = System.currentTimeMillis(); } else misses++;
    }
}
