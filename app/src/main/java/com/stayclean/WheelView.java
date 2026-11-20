package com.stayclean;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.Interpolator;

/**
 * One drum of the picker. Items curve away from the centre like a real wheel, flick with momentum, and always settle
 * on a row. Every row that passes the middle gives a light haptic tick and a soft click.
 */
final class WheelView extends View {
    interface Listener { void onSettled(int index); }

    private final String[] items;
    private final String unit;
    private final Paint num = new Paint(Paint.ANTI_ALIAS_FLAG), lab = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float rowH;
    private final TickSound sound;
    private Listener listener;
    private float pos;                 // fractional row index at the centre line
    private int lastTick;
    private float downY, lastY;
    private boolean dragging;
    private VelocityTracker vt;
    private ValueAnimator anim;

    /** Soft expo-out: keeps the speed you let go with, then glides to rest. */
    private static final Interpolator GLIDE = new Interpolator() {
        @Override public float getInterpolation(float x) { return (float) ((1 - Math.exp(-5 * x)) / (1 - Math.exp(-5))); }
    };

    WheelView(Context c, String[] items, String unit, int start, TickSound sound) {
        super(c);
        this.items = items; this.unit = unit; this.sound = sound;
        this.rowH = Overlay.dp(c, 44);
        num.setColor(0xFFFFFFFF);
        num.setTextAlign(Paint.Align.RIGHT);
        num.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 26, c.getResources().getDisplayMetrics()));
        lab.setColor(0xFFFFFFFF);
        lab.setTextAlign(Paint.Align.LEFT);
        lab.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, c.getResources().getDisplayMetrics()));
        try { num.setTypeface(c.getResources().getFont(R.font.inter_regular)); lab.setTypeface(c.getResources().getFont(R.font.inter_semibold)); }
        catch (Throwable t) { num.setTypeface(Typeface.SANS_SERIF); lab.setTypeface(Typeface.SANS_SERIF); }
        pos = start; lastTick = start;
    }

    void setListener(Listener l) { listener = l; }
    int index() { return Math.round(pos); }
    void snapTo(int i) { cancel(); pos = Math.max(0, Math.min(items.length - 1, i)); lastTick = Math.round(pos); invalidate(); }
    void glideTo(int i) { animateTo(Math.max(0, Math.min(items.length - 1, i)), 0f); }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(Overlay.dp(getContext(), 120), w), Math.round(rowH * 5));
    }

    @Override protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, nx = getWidth() * 0.52f;
        int from = (int) Math.floor(pos) - 3, to = (int) Math.ceil(pos) + 3;
        for (int i = from; i <= to; i++) {
            if (i < 0 || i >= items.length) continue;
            float d = i - pos;                                   // rows from the centre line
            float ang = Math.max(-1.25f, Math.min(1.25f, d * 0.46f));
            float k = (float) Math.cos(ang);
            float y = cy + (float) Math.sin(ang) * rowH / 0.46f;
            num.setAlpha((int) (255 * Math.max(0.12f, k * k * k)));
            c.save();
            c.scale(1f, k, nx, y);
            c.drawText(items[i], nx, y + num.getTextSize() * 0.36f, num);
            c.restore();
        }
        c.drawText(unit, nx + Overlay.dp(getContext(), 8), cy + lab.getTextSize() * 0.36f, lab);
    }

    private void cancel() { if (anim != null) { anim.cancel(); anim = null; } }

    private void tickIfRowChanged() {
        int r = Math.round(pos);
        if (r != lastTick) {
            lastTick = r;
            try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING); } catch (Throwable ignored) {}
            if (sound != null) sound.play();
        }
    }

    private void animateTo(final int target, float rowsPerSec) {
        cancel();
        final float from = pos, dist = target - from;
        if (Math.abs(dist) < 0.001f) { pos = target; finish(); return; }
        long ms = (long) Math.max(260, Math.min(1100, 230 + Math.sqrt(Math.abs(dist)) * 300));
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(ms);
        anim.setInterpolator(GLIDE);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pos = from + dist * (float) a.getAnimatedValue();
                tickIfRowChanged();
                invalidate();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) { pos = target; invalidate(); finish(); } }
        });
        anim.start();
    }

    private void finish() { if (listener != null) listener.onSettled(Math.round(pos)); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                getParent().requestDisallowInterceptTouchEvent(true);
                dragging = false; downY = lastY = e.getY();
                vt = VelocityTracker.obtain(); vt.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                vt.addMovement(e);
                float dy = e.getY() - lastY; lastY = e.getY();
                if (!dragging && Math.abs(e.getY() - downY) > Overlay.dp(getContext(), 4)) dragging = true;
                if (dragging) {
                    float step = -dy / rowH;
                    boolean over = (pos < 0 && step < 0) || (pos > items.length - 1 && step > 0);
                    pos += over ? step * 0.35f : step;                    // rubber band past the ends
                    pos = Math.max(-0.7f, Math.min(items.length - 1 + 0.7f, pos));
                    tickIfRowChanged();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                vt.addMovement(e);
                vt.computeCurrentVelocity(1000);
                float v = -vt.getYVelocity() / rowH;                      // rows per second
                vt.recycle(); vt = null;
                if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    int tapped = Math.round(pos + (e.getY() - getHeight() / 2f) / rowH);   // tap a row to select it
                    animateTo(Math.max(0, Math.min(items.length - 1, tapped)), 0f);
                } else {
                    int t = Math.round(pos + v / 4.2f);                    // where momentum would carry it
                    animateTo(Math.max(0, Math.min(items.length - 1, t)), v);
                }
                dragging = false;
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
