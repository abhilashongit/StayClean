package com.stayclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 10:50 and 10:55 pm: a gentle heads-up that Instagram and X rest at 11. Then arm the next one. */
public class WindDownReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try {
            long at = i == null ? 0 : i.getLongExtra("at", 0);
            int min = i == null ? 0 : i.getIntExtra("min", 0);
            boolean fresh = Math.abs(System.currentTimeMillis() - at) < 4 * 60_000L;   // skip if delivered very late
            if (Util.socialOn(c) && fresh && (min == 10 || min == 5)) Notes.windDown(c, min);
        } catch (Throwable ignored) {}
        Notes.armWindDown(c);
    }
}
