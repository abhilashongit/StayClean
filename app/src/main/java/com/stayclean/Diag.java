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
    /** Reddit probe: the last few distinct screen headers seen in the Reddit app (newest first). */
    public static final java.util.LinkedList<String> redditScreens = new java.util.LinkedList<>();
    public static synchronized void redditScreen(String sig) {
        if (sig == null || sig.isEmpty() || (!redditScreens.isEmpty() && redditScreens.getFirst().equals(sig))) return;
        redditScreens.remove(sig);
        redditScreens.addFirst(sig);
        while (redditScreens.size() > 4) redditScreens.removeLast();
    }
    public static synchronized String redditScreensText() {
        StringBuilder b = new StringBuilder();
        for (String s : redditScreens) b.append("\n  \u2022 ").append(s);
        return b.toString();
    }
    public static volatile int redditSearches;
    /** Bank Pass: times Stay Clean stepped aside for a bank app this run. */
    public static volatile int bankPauses;

    public static void onBrowserRead(String pkg, boolean ok) {
        browserPkg = pkg;
        if (ok) { reads++; lastReadAt = System.currentTimeMillis(); } else misses++;
    }
}
