package com.stayclean;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Minimal setup screen. Black, Inter, six rows. Do them top to bottom; Guard goes last. */
public class MainActivity extends Activity {
    private static final int GRAY = Color.rgb(140, 140, 140);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Util.applyPhase(this);
        Util.syncIfStale(this);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        final Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Overlay.dp(c, 28);
        col.setPadding(pad, Overlay.dp(c, 64), pad, Overlay.dp(c, 40));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Overlay.text(c, "STAY CLEAN", R.font.inter_semibold, 28, Color.WHITE, 0.30f);
        title.setGravity(Gravity.START);
        col.addView(title);

        TextView sub = Overlay.text(c, "Swiggy and Zomato stay closed\nfrom 12 am to 5 am IST.", R.font.inter_light, 15, GRAY, 0.02f);
        sub.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) sub.getLayoutParams()).topMargin = Overlay.dp(c, 12);
        col.addView(sub);

        boolean locked = Util.blockedNow(c);
        TextView state = Overlay.text(c, locked ? "Locked now" : "Open now", R.font.inter_medium, 13, locked ? Color.WHITE : GRAY, 0.08f);
        state.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) state.getLayoutParams()).topMargin = Overlay.dp(c, 28);
        ((LinearLayout.LayoutParams) state.getLayoutParams()).bottomMargin = Overlay.dp(c, 12);
        col.addView(state);

        toggleRow(col);
        infoRow(col, "Instagram limit", "5 minutes in every 30", "Always");
        TextView setup = Overlay.text(c, "SETUP", R.font.inter_medium, 11, GRAY, 0.2f);
        setup.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) setup.getLayoutParams()).topMargin = Overlay.dp(c, 28);
        col.addView(setup);

        // Order matters: the lock-in of this app's settings pages starts once Guard is on.
        if (Build.VERSION.SDK_INT >= 33) {
            row(col, "1", "Notifications", "Keeps the guard visible and alive",
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 0,
                    new Runnable() { public void run() { requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); } });
        }
        row(col, "2", "Cover screen", "Display over other apps",
                Settings.canDrawOverlays(c),
                new Runnable() { public void run() { open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } });
        row(col, "3", "Backup guard", "Usage access, takes over if Guard stops",
                hasUsage(),
                new Runnable() { public void run() { open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); } });
        final DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        final ComponentName admin = new ComponentName(c, AdminReceiver.class);
        row(col, "4", "Lock uninstall", "Device admin, uninstall needs it removed first",
                dpm.isAdminActive(admin),
                new Runnable() { public void run() {
                    open(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Stops Stay Clean from being uninstalled on a whim."));
                } });
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        row(col, "5", "Stay alive", "Battery: unrestricted",
                pm.isIgnoringBatteryOptimizations(getPackageName()),
                new Runnable() { public void run() { open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } });
        boolean acc = Util.accessibilityEnabled(c);
        row(col, "6", "Guard", "Accessibility, closes the apps instantly",
                acc,
                new Runnable() { public void run() { open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } });

        if (!acc && Build.VERSION.SDK_INT >= 33) {
            TextView hint = Overlay.text(c, "If Guard is greyed out: Settings, Apps, Stay Clean, three dots, Allow restricted settings.",
                    R.font.inter_light, 12, GRAY, 0.02f);
            hint.setGravity(Gravity.START);
            ((LinearLayout.LayoutParams) hint.getLayoutParams()).topMargin = Overlay.dp(c, 20);
            col.addView(hint);
        }
        TextView foot = Overlay.text(c, "Awake 10:55 pm to 5:05 am. Asleep the rest of the day.\nFrom 11 pm to 6 am, this app's settings pages stay shut. Updates install any time.",
                R.font.inter_light, 12, GRAY, 0.02f);
        foot.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) foot.getLayoutParams()).topMargin = Overlay.dp(c, 16);
        col.addView(foot);

        TextView chai = Overlay.text(c, "Buy me a chai \u00b7 \u20b910", R.font.inter_light, 12, Color.rgb(110, 110, 110), 0.02f);
        chai.setGravity(Gravity.START);
        chai.setPadding(0, Overlay.dp(c, 10), Overlay.dp(c, 16), Overlay.dp(c, 10));
        ((LinearLayout.LayoutParams) chai.getLayoutParams()).topMargin = Overlay.dp(c, 14);
        chai.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { supportChai(); } });
        col.addView(chai);

        TextView diag = Overlay.text(c, diagText(), R.font.inter_light, 11, Color.rgb(90, 90, 90), 0.02f);
        diag.setGravity(Gravity.START);
        ((LinearLayout.LayoutParams) diag.getLayoutParams()).topMargin = Overlay.dp(c, 24);
        col.addView(diag);

        setContentView(sv);
    }

    private void row(LinearLayout parent, String n, String label, String desc, boolean ok, final Runnable action) {
        Context c = this;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int v = Overlay.dp(c, 14);
        r.setPadding(0, v, 0, v);

        TextView num = Overlay.text(c, n, R.font.inter_light, 13, GRAY, 0f);
        num.setLayoutParams(new LinearLayout.LayoutParams(Overlay.dp(c, 26), ViewGroup.LayoutParams.WRAP_CONTENT));
        num.setGravity(Gravity.START);
        r.addView(num);

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView l = Overlay.text(c, label, R.font.inter_medium, 16, Color.WHITE, 0.01f);
        l.setGravity(Gravity.START);
        TextView d = Overlay.text(c, desc, R.font.inter_light, 12, GRAY, 0.01f);
        d.setGravity(Gravity.START);
        mid.addView(l); mid.addView(d);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView st = Overlay.text(c, ok ? "On" : "Set up", ok ? R.font.inter_semibold : R.font.inter_regular, 13,
                ok ? Color.WHITE : GRAY, 0.04f);
        r.addView(st);

        if (!ok) {
            r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View x) { action.run(); } });
        }
        parent.addView(r);
        View line = new View(c);
        line.setBackgroundColor(Color.rgb(28, 28, 28));
        parent.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    /** Night lock for Instagram + X: switch on any time, off only between 6 am and 11 pm. */
    private void toggleRow(LinearLayout parent) {
        final Context c = this;
        final boolean on = Util.socialOn(c);
        final boolean canOff = Util.canDisableSocial(c);
        String desc = on && !canOff ? "Instagram and X, held until morning" : "Instagram and X, 11 pm to 5 am";
        LinearLayout r = baseRow(parent, "Night lock", desc, on ? "On" : "Off", on);
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!on) Util.setSocialOn(c, true);
                else if (Util.canDisableSocial(c)) Util.setSocialOn(c, false);
                render();
            }
        });
    }

    private void infoRow(LinearLayout parent, String label, String desc, String right) {
        baseRow(parent, label, desc, right, true);
    }

    private LinearLayout baseRow(LinearLayout parent, String label, String desc, String right, boolean strong) {
        Context c = this;
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int v = Overlay.dp(c, 14);
        r.setPadding(0, v, 0, v);
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView l = Overlay.text(c, label, R.font.inter_medium, 16, Color.WHITE, 0.01f);
        l.setGravity(Gravity.START);
        TextView d = Overlay.text(c, desc, R.font.inter_light, 12, GRAY, 0.01f);
        d.setGravity(Gravity.START);
        mid.addView(l); mid.addView(d);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Overlay.text(c, right, strong ? R.font.inter_semibold : R.font.inter_regular, 13, strong ? Color.WHITE : GRAY, 0.04f));
        parent.addView(r);
        View line = new View(c);
        line.setBackgroundColor(Color.rgb(28, 28, 28));
        parent.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return r;
    }

    private String diagText() {
        String v = "";
        try { v = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        StringBuilder s = new StringBuilder(v);
        if (Diag.reads == 0 && Diag.misses == 0) s.append("\nBrowser check: no browser seen yet");
        else if (Diag.reads > 0) s.append("\nBrowser check: reading (").append(Diag.reads).append(")");
        else s.append("\nBrowser check: cannot read the address bar (").append(Diag.misses).append(")");
        if (Diag.lastAction.length() > 0) s.append("\nLast tab close: ").append(Diag.lastAction);
        return s.toString();
    }

    private void supportChai() {
        Uri u = Uri.parse("upi://pay?pa=theabhilashkar@okhdfcbank&pn=Stay%20Clean&am=10.00&cu=INR&tn=Stay%20Clean%20support");
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, u), "Pay \u20b910 with"));
        } catch (Throwable t) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("UPI ID", "theabhilashkar@okhdfcbank"));
                android.widget.Toast.makeText(this, "UPI ID copied", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        }
    }

    private void open(Intent i) {
        try { startActivity(i); } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignored) {}
        }
    }

    private boolean hasUsage() {
        try {
            AppOpsManager a = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) { return false; }
    }
}
