package com.stayclean;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Morning glow: the first unlock of the day at or after 6:00 IST (and before noon) shows a soft warm strip across
 * the bottom of the screen for a few seconds. Cosmetic only: it never touches the guard, takes no touches, and the
 * screen above it stays whatever you were on. Once per IST day.
 */
final class Morning {
    private Morning() {}

    static final int START_MIN = 6 * 60;     // 6:00 IST
    static final int END_MIN = 12 * 60;      // no greeting from noon on
    private static final long DELAY_MS = 450, FADE_IN_MS = 800, HOLD_MS = 2400, FADE_OUT_MS = 700;

    private static Handler handler;     // created on first use, so the pure logic above stays testable off-device
    private static synchronized Handler H() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }
    private static volatile boolean showing;

    // ---- pure logic (unit-tested) ----

    static long dayOf(long epochMs) { return Math.floorDiv(epochMs + Schedule.IST_OFFSET_MS, 86_400_000L); }

    static int minuteOfDay(long epochMs) {
        return (int) (Math.floorMod(epochMs + Schedule.IST_OFFSET_MS, 86_400_000L) / 60_000L);
    }

    /** True when an unlock at nowMs should greet: inside the 6:00 to noon window, and not yet greeted today. */
    static boolean due(long nowMs, long lastGreetedDay) {
        int m = minuteOfDay(nowMs);
        return m >= START_MIN && m < END_MIN && dayOf(nowMs) != lastGreetedDay;
    }

    // ---- wiring ----

    /** Call on every unlock (USER_PRESENT). Cheap: one clock read and one in-memory preference read. */
    static void onUnlock(final Context svc) {
        try {
            long now = System.currentTimeMillis();
            final SharedPreferences sp = svc.getApplicationContext().getSharedPreferences("morning", Context.MODE_PRIVATE);
            if (showing || !due(now, sp.getLong("day", Long.MIN_VALUE))) return;
            if (Overlay.busy()) return;
            sp.edit().putLong("day", dayOf(now)).apply();     // record first: a crash can never make it repeat
            H().postDelayed(new Runnable() {
                @Override public void run() { display(svc, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY); }
            }, DELAY_MS);                                      // let the home screen draw first, then the glow rises over it
        } catch (Throwable ignored) {}
    }

    private static void display(final Context ctx, int type) {
        if (showing) return;
        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        final FrameLayout root = build(ctx);
        int h = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.38f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        try {
            root.setAlpha(0f);
            wm.addView(root, lp);
        } catch (Throwable t) { return; }
        showing = true;
        Diag.morningShown++;
        root.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        H().postDelayed(new Runnable() {
            @Override public void run() {
                root.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(new Runnable() {
                    @Override public void run() {
                        try { wm.removeViewImmediate(root); } catch (Throwable ignored) {}
                        showing = false;
                    }
                }).start();
            }
        }, FADE_IN_MS + HOLD_MS);
    }

    private static FrameLayout build(Context c) {
        FrameLayout root = new FrameLayout(c);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00B8793C, 0x1FB8793C, 0x66A8662F, 0xB3744520});
        root.setBackground(glow);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Overlay.text(c, "Good morning", R.font.inter_semibold, 26, Color.rgb(242, 237, 232), 0.0f));
        TextView sub = Overlay.text(c, "Everything is back.", R.font.inter_light, 15, Color.rgb(222, 212, 200), 0.03f);
        ((LinearLayout.LayoutParams) sub.getLayoutParams()).topMargin = Overlay.dp(c, 8);
        col.addView(sub);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.bottomMargin = Overlay.dp(c, 64);
        root.addView(col, lp);
        return root;
    }
}
