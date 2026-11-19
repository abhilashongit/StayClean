package com.stayclean;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Phase alarm, boot, app update, clock/zone change: move to the right phase, re-arm one alarm, re-sync time if needed. */
public class KeepAliveReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context ctx, Intent intent) {
        final String a = intent == null ? null : intent.getAction();
        Util.applyPhase(ctx);
        if (a != null && !Util.ACTION_KEEPALIVE.equals(a)) {         // boot / time change: anchor again, then re-apply
            final PendingResult pr = goAsync();
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Util.syncNow(ctx.getApplicationContext()); Util.applyPhase(ctx); }
                    finally { pr.finish(); }
                }
            }).start();
        }
    }
}
