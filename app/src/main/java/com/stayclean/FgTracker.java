package com.stayclean;

/** Tracks the foreground package from a stream of UsageEvents (fallback path when accessibility is off). */
public final class FgTracker {
    public static final int RESUMED = 1;   // UsageEvents.Event.MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
    public static final int PAUSED = 2;    // UsageEvents.Event.MOVE_TO_BACKGROUND / ACTIVITY_PAUSED

    private String fg;

    public void onEvent(String pkg, int type) {
        if (pkg == null) return;
        if (type == RESUMED) fg = pkg;
        else if (type == PAUSED && pkg.equals(fg)) fg = null;
    }

    /** Pretend we just sent the user Home. */
    public void clear() { fg = null; }

    public String current() { return fg; }
}
