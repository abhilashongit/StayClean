package com.stayclean;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Bottom sheet with two wheels (minutes, seconds) for the Instagram allowance. Slides up like an iOS sheet. */
final class LimitSheet {
    private static final int AMBER = Color.rgb(232, 161, 90), GRAY = Color.rgb(140, 140, 140);
    private static final PathInterpolator APPLE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    private final Activity act;
    private final Runnable onSaved;
    private FrameLayout scrim;
    private LinearLayout sheet;
    private WheelView mins, secs;
    private TickSound sound;
    private boolean closing;

    LimitSheet(Activity act, Runnable onSaved) { this.act = act; this.onSaved = onSaved; }

    boolean isOpen() { return scrim != null && !closing; }

    void show() {
        if (scrim != null) return;
        final Context c = act;
        long cur = Util.socialLimitMs(c);
        int m = (int) (cur / 60_000L), s = (int) ((cur % 60_000L) / 30_000L);
        sound = new TickSound();

        scrim = new FrameLayout(c);
        scrim.setBackgroundColor(0x99000000);
        scrim.setAlpha(0f);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dismiss(); } });

        sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 28, 30));
        float r = Overlay.dp(c, 26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setClickable(true);
        sheet.setPadding(Overlay.dp(c, 22), Overlay.dp(c, 18), Overlay.dp(c, 22), Overlay.dp(c, 30));

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = Overlay.text(c, "Cancel", R.font.inter_regular, 16, AMBER, 0f);
        TextView title = Overlay.text(c, "Instagram time", R.font.inter_semibold, 16, Color.WHITE, 0f);
        TextView save = Overlay.text(c, "Save", R.font.inter_semibold, 16, AMBER, 0f);
        cancel.setPadding(0, Overlay.dp(c, 8), Overlay.dp(c, 16), Overlay.dp(c, 8));
        save.setPadding(Overlay.dp(c, 16), Overlay.dp(c, 8), 0, Overlay.dp(c, 8));
        head.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ((LinearLayout.LayoutParams) cancel.getLayoutParams()).gravity = Gravity.START;
        cancel.setGravity(Gravity.START);
        head.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        save.setGravity(Gravity.END);
        sheet.addView(head);

        TextView sub = Overlay.text(c, "How long it stays open in every 30 minutes", R.font.inter_light, 13, GRAY, 0.01f);
        ((LinearLayout.LayoutParams) sub.getLayoutParams()).topMargin = Overlay.dp(c, 6);
        sheet.addView(sub);

        FrameLayout wheels = new FrameLayout(c);
        View band = new View(c);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(Color.rgb(44, 44, 46));
        bb.setCornerRadius(Overlay.dp(c, 12));
        band.setBackground(bb);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Overlay.dp(c, 44), Gravity.CENTER_VERTICAL);
        wheels.addView(band, bl);
        String[] mi = {"0", "1", "2", "3", "4", "5", "6", "7"};
        String[] se = {"00", "30"};
        mins = new WheelView(c, mi, "min", m, sound);
        secs = new WheelView(c, se, "sec", s, sound);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(mins, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(secs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wheels.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = Overlay.dp(c, 14);
        sheet.addView(wheels, wl);

        // 7 min 30 sec is the ceiling: on 7 min the seconds can only be 00 or 30, which already fits (7:30 = max).
        TextView note = Overlay.text(c, "Up to 7 min 30 sec. You can change this once a day.\nRaising it opens from 6 am.", R.font.inter_light, 12, GRAY, 0.01f);
        note.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) note.getLayoutParams()).topMargin = Overlay.dp(c, 12);
        sheet.addView(note);

        cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dismiss(); } });
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });

        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        scrim.addView(sheet, sl);
        ((ViewGroup) act.findViewById(android.R.id.content)).addView(scrim,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sheet.setVisibility(View.INVISIBLE);
        sheet.post(new Runnable() {
            @Override public void run() {
                if (sheet == null) return;
                sheet.setTranslationY(sheet.getHeight());
                sheet.setVisibility(View.VISIBLE);
                scrim.animate().alpha(1f).setDuration(320).start();
                sheet.animate().translationY(0).setDuration(460).setInterpolator(APPLE).start();
            }
        });
    }

    private long chosenMs() { return mins.index() * 60_000L + secs.index() * 30_000L; }

    private void save() {
        long next = SocialLimit.clamp(chosenMs());
        SocialLimit.Verdict v = Util.checkSocialLimit(act, next);
        switch (v) {
            case SAME: dismiss(); return;
            case ALREADY_TODAY: toast("Already changed today. Back tomorrow."); return;
            case RAISE_AT_NIGHT: toast("Raising it opens at 6 am."); return;
            default: break;
        }
        Util.setSocialLimit(act, next);
        try { sheet.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING); } catch (Throwable ignored) {}
        dismiss();
        if (onSaved != null) act.getWindow().getDecorView().postDelayed(onSaved, 400);   // refresh the row after the sheet has slid away
    }

    private void toast(String s) {
        try { sheet.performHapticFeedback(HapticFeedbackConstants.REJECT, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING); } catch (Throwable ignored) {}
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }

    void dismiss() {
        if (scrim == null || closing) return;
        closing = true;
        final FrameLayout s = scrim;
        scrim.animate().alpha(0f).setDuration(280).start();
        sheet.animate().translationY(sheet.getHeight()).setDuration(340).setInterpolator(APPLE).withEndAction(new Runnable() {
            @Override public void run() { remove(s); }
        }).start();
    }

    /** Immediate teardown (screen rebuilt, activity leaving). */
    void abort() { if (scrim != null) remove(scrim); }

    private void remove(FrameLayout s) {
        try { ((ViewGroup) s.getParent()).removeView(s); } catch (Throwable ignored) {}
        if (sound != null) sound.release();
        sound = null; scrim = null; sheet = null; closing = false;
    }
}
