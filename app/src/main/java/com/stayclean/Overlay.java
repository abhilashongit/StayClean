package com.stayclean;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen black cover: STAY CLEAN + a kind line. Pre-built once, reused; text fades in, then Home, then fade out. */
final class Overlay {
    private Overlay() {}

    static final String TITLE = "STAY CLEAN";
    private static final long FADE_IN_MS = 600, HOLD_MS = 1800, FADE_OUT_MS = 300;

    private static final Gate gate = new Gate();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final SparseArray<Holder> cache = new SparseArray<>();

    private static final class Holder {
        Context ctx; LinearLayout root; TextView title, sub;
    }

    static boolean busy() { return gate.isBusy(); }

    /** Build the view ahead of time so the first block is as fast as the tenth. */
    static void prewarm(final Context ctx, final int type) {
        H.post(new Runnable() { @Override public void run() { try { holder(ctx, type); } catch (Throwable ignored) {} } });
    }

    private static Holder holder(Context ctx, int type) {
        Holder h = cache.get(type);
        if (h != null && h.ctx == ctx) return h;
        h = new Holder();
        h.ctx = ctx;
        h.root = new LinearLayout(ctx);
        h.root.setOrientation(LinearLayout.VERTICAL);
        h.root.setGravity(Gravity.CENTER);
        h.root.setBackgroundColor(Color.BLACK);
        h.root.setClickable(true);
        h.root.setPadding(dp(ctx, 36), 0, dp(ctx, 36), 0);
        h.root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        h.title = text(ctx, TITLE, R.font.inter_semibold, 30, Color.WHITE, 0.30f);
        h.sub = text(ctx, "", R.font.inter_light, 15, Color.rgb(140, 140, 140), 0.04f);
        ((LinearLayout.LayoutParams) h.sub.getLayoutParams()).topMargin = dp(ctx, 14);
        h.root.addView(h.title);
        h.root.addView(h.sub);
        cache.put(type, h);
        return h;
    }

    /** Covers the screen at once, then runs goHome and kills the app's process. Single-flight. */
    static void show(Context ctx, int windowType, String pkg, Runnable goHome) { show(ctx, windowType, pkg, goHome, null); }

    /** line: the small text under STAY CLEAN; null picks a food line. */
    static void show(final Context ctx, final int windowType, final String pkg, final Runnable goHome, final String line) {
        if (!gate.tryAcquire()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doShow(ctx, windowType, pkg, goHome, line);
        } else {
            H.post(new Runnable() { @Override public void run() { doShow(ctx, windowType, pkg, goHome, line); } });
        }
    }

    interface Work { void run(Runnable done); }

    /**
     * Silent cover: plain black, no text. Hands `work` a `done` callback; the cover lifts when done runs
     * (or after maxHoldMs as a failsafe).
     */
    static void cover(final Context ctx, final int type, final long maxHoldMs, final Work work) {
        if (!gate.tryAcquire()) return;
        Runnable go = new Runnable() {
            @Override public void run() {
                final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                final Holder hd;
                final boolean[] lifted = {false};
                try {
                    hd = holder(ctx, type);
                    hd.root.animate().cancel(); hd.title.animate().cancel(); hd.sub.animate().cancel();
                    hd.root.setAlpha(1f); hd.title.setAlpha(0f); hd.sub.setAlpha(0f);
                    if (hd.root.getParent() != null) { try { wm.removeViewImmediate(hd.root); } catch (Throwable ignored) {} }
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            PixelFormat.OPAQUE);
                    if (Build.VERSION.SDK_INT >= 28) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    }
                    wm.addView(hd.root, lp);
                } catch (Throwable t) {
                    try { work.run(new Runnable() { @Override public void run() {} }); } catch (Throwable ignored) {}
                    gate.release();
                    return;
                }
                final Runnable lift = new Runnable() {
                    @Override public void run() {
                        if (lifted[0]) return;
                        lifted[0] = true;
                        hd.root.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                            @Override public void run() {
                                try { wm.removeViewImmediate(hd.root); } catch (Throwable ignored) {}
                                gate.release();
                            }
                        }).start();
                    }
                };
                try { work.run(lift); } catch (Throwable t) { lift.run(); }
                H.postDelayed(lift, maxHoldMs);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) go.run(); else H.post(go);
    }

    static void killApp(Context ctx, String pkg) { kill(ctx, pkg); }

    private static void doShow(final Context ctx, int type, final String pkg, final Runnable goHome, final String line) {
        final WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        final Holder hd;
        try {
            hd = holder(ctx, type);
            hd.root.animate().cancel();
            hd.title.animate().cancel();
            hd.sub.animate().cancel();
            hd.root.setAlpha(1f);
            hd.title.setAlpha(0f);
            hd.sub.setAlpha(0f);
            hd.sub.setText(line != null ? line : Phrases.DEFAULT.next());
            if (hd.root.getParent() != null) { try { wm.removeViewImmediate(hd.root); } catch (Throwable ignored) {} }

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.OPAQUE);
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            wm.addView(hd.root, lp);
            hd.title.animate().alpha(1f).setDuration(FADE_IN_MS).start();
            hd.sub.animate().alpha(1f).setStartDelay(200).setDuration(FADE_IN_MS).start();
        } catch (Throwable t) {
            // Could not draw (permission missing): still close the app.
            try { goHome.run(); } catch (Throwable ignored) {}
            kill(ctx, pkg);
            gate.release();
            return;
        }

        H.postDelayed(new Runnable() {
            @Override public void run() {
                try { goHome.run(); } catch (Throwable ignored) {}
                H.postDelayed(new Runnable() { @Override public void run() { kill(ctx, pkg); } }, 200);
                H.postDelayed(new Runnable() {
                    @Override public void run() {
                        hd.root.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(new Runnable() {
                            @Override public void run() {
                                try { wm.removeViewImmediate(hd.root); } catch (Throwable ignored) {}
                                gate.release();
                            }
                        }).start();
                    }
                }, 350);
            }
        }, HOLD_MS);
    }

    private static void kill(Context ctx, String pkg) {
        if (!Targets.isKillable(pkg)) return;
        try { ((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); }
        catch (Throwable ignored) {}
    }

    static TextView text(Context c, String s, int fontRes, int sp, int color, float spacing) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setLetterSpacing(spacing);
        t.setGravity(Gravity.CENTER);
        try { t.setTypeface(c.getResources().getFont(fontRes)); } catch (Throwable ignored) { t.setTypeface(Typeface.SANS_SERIF); }
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
