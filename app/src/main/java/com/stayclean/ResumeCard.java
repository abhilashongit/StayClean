package com.stayclean;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * While Bank Pass has Stay Clean paused: a calm card that rises from the bottom after an unlock, in the same warm
 * glow as the morning greeting. One tap opens the switch. It fades on its own; nothing is blocked.
 */
final class ResumeCard {
    private ResumeCard() {}

    static final String[] LINES = {
            "Your bank is done. Shall we pick up where we left off?",
            "Stay Clean is resting. One tap brings it back.",
            "Still paused. Tap once and you are covered again.",
            "A small favour for tonight's you: switch it back on.",
            "Everything else is ready. Only the switch is waiting."
    };
    private static int next;
    private static Handler handler;     // made on first use, so the pure parts stay testable off-device
    private static synchronized Handler H() { if (handler == null) handler = new Handler(Looper.getMainLooper()); return handler; }
    private static View showing;

    static String line() { String s = LINES[next % LINES.length]; next++; return s; }

    /** The accessibility screen, scrolled to Stay Clean where the system supports it. */
    static Intent switchIntent(Context c) {
        Intent s = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String comp = c.getPackageName() + "/" + GuardService.class.getName();
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", comp);
        s.putExtra(":settings:fragment_args_key", comp);
        s.putExtra(":settings:show_fragment_args", args);
        return s;
    }

    static void show(final Context c, String text) {
        if (showing != null || !Settings.canDrawOverlays(c)) return;
        final WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        final FrameLayout root = new FrameLayout(c);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF21C1712, 0xF2120E0A});
        bg.setCornerRadius(Overlay.dp(c, 24));
        bg.setStroke(Overlay.dp(c, 1), 0x33E8A15A);
        card.setBackground(bg);
        int p = Overlay.dp(c, 20);
        card.setPadding(p, p, p, p);

        TextView title = Overlay.text(c, "Stay Clean is paused", R.font.inter_semibold, 17, Color.rgb(242, 237, 232), 0f);
        title.setGravity(Gravity.START);
        TextView body = Overlay.text(c, text, R.font.inter_light, 14, Color.rgb(205, 196, 186), 0.01f);
        body.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) body.getLayoutParams()).topMargin = Overlay.dp(c, 6);
        TextView cta = Overlay.text(c, "Switch back on", R.font.inter_semibold, 14, Color.rgb(20, 14, 8), 0.02f);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFFE8A15A);
        pill.setCornerRadius(Overlay.dp(c, 22));
        cta.setBackground(pill);
        cta.setPadding(Overlay.dp(c, 18), Overlay.dp(c, 10), Overlay.dp(c, 18), Overlay.dp(c, 10));
        LinearLayout.LayoutParams cl = (LinearLayout.LayoutParams) cta.getLayoutParams();
        cl.topMargin = Overlay.dp(c, 14);
        cl.gravity = Gravity.START;
        card.addView(title); card.addView(body); card.addView(cta);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int m = Overlay.dp(c, 14);
        lp.setMargins(m, 0, m, Overlay.dp(c, 28));
        root.addView(card, lp);

        final Runnable close = new Runnable() {
            @Override public void run() {
                if (showing != root) return;
                root.animate().alpha(0f).translationY(Overlay.dp(c, 24)).setDuration(420).withEndAction(new Runnable() {
                    @Override public void run() { try { wm.removeViewImmediate(root); } catch (Throwable ignored) {} showing = null; }
                }).start();
            }
        };
        View.OnClickListener go = new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { c.startActivity(switchIntent(c)); } catch (Throwable ignored) {}
                H().removeCallbacks(close); close.run();
            }
        };
        card.setOnClickListener(go);
        cta.setOnClickListener(go);

        WindowManager.LayoutParams wl = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        wl.gravity = Gravity.BOTTOM;
        try {
            root.setAlpha(0f);
            root.setTranslationY(Overlay.dp(c, 40));
            wm.addView(root, wl);
        } catch (Throwable t) { return; }
        showing = root;
        root.animate().alpha(1f).translationY(0).setDuration(520)
                .setInterpolator(new android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)).start();
        H().postDelayed(close, 8_000);
    }

    static void hide() {
        View v = showing;
        if (v == null) return;
        try { ((WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE)).removeViewImmediate(v); } catch (Throwable ignored) {}
        showing = null;
    }
}
