package com.stayclean;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Primary guard, event-driven. Window changes arrive instantly; a short safety poll runs only while something
 * that needs watching is on screen (a browser, Instagram, a blocked app at night, a Settings page at night).
 */
public class GuardService extends AccessibilityService {
    static volatile boolean alive;
    static volatile GuardService instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Schedule.Phase applied;
    private volatile String lastPkg;
    private boolean lastWasIg, rxOn;
    private int igSamples;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long next = -1;
            try { next = tickOnce(); } catch (Throwable ignored) {}
            if (next > 0) h.postDelayed(this, next);
        }
    };
    private final Runnable oneShot = new Runnable() {
        @Override public void run() { try { tickOnce(); } catch (Throwable ignored) {} }
    };
    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { kick(); }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        alive = true;
        instance = this;
        Overlay.prewarm(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenRx, f);
            rxOn = true;
        } catch (Throwable ignored) {}
        Util.applyPhase(this);
    }

    /** DAY: listen only to the watched apps. Otherwise: every window change (needed for the Settings lock). */
    void applyPhase(Schedule.Phase p) {
        if (p != applied) {
            try {
                AccessibilityServiceInfo i = getServiceInfo();
                if (i != null) {
                    i.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                    i.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                            | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                            | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                    i.packageNames = (p == Schedule.Phase.DAY) ? Targets.dayFilter() : null;
                    setServiceInfo(i);
                }
                applied = p;
            } catch (Throwable ignored) {}
        }
        kick();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        lastPkg = pkg;

        if (Targets.isBlockedApp(pkg)) {                         // fastest path: no IPC, cover now
            if (Util.blockedNow(this)) {
                if (applied != Schedule.Phase.ACTIVE) Util.applyPhase(this);   // self-heal if an alarm was missed
                enforce(pkg, null);
            }
            return;
        }
        if (Targets.isSocialApp(pkg) && Util.socialNightNow(this)) {
            if (applied != Schedule.Phase.ACTIVE) Util.applyPhase(this);
            enforce(pkg, Phrases.SOCIAL.next());
            return;
        }
        if (Targets.isInstagram(pkg) || Targets.isBrowser(pkg)) { kick(); return; }
        if (Targets.isTamperHost(pkg) && Util.tamperLockNow(this)) {
            kick();                                   // check now, then keep checking while on this page
            h.removeCallbacks(oneShot);
            h.postDelayed(oneShot, 350);              // pages populate a moment after they open
        }
    }

    /** Returns ms until the next check, or -1 when nothing needs watching. */
    private long tickOnce() {
        String lp = lastPkg;
        boolean blocked = Util.blockedNow(this), tamper = Util.tamperLockNow(this), social = Util.socialNightNow(this);
        boolean watch = blocked || social || lastWasIg || Targets.isInstagram(lp) || Targets.isBrowser(lp)
                || (tamper && Targets.isTamperHost(lp));
        if (!watch) return -1;
        long idle = (blocked || social) ? 5000 : -1;

        if (!interactive()) { endIgSession(); return idle; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = (root != null && root.getPackageName() != null) ? root.getPackageName().toString() : null;
        if (pkg == null || pkg.equals(getPackageName())) {
            if (Targets.isBrowser(lp)) return 700;           // keep polling through transient empty screens
            return lastWasIg ? 1000 : idle;
        }
        lastPkg = pkg;
        if (lastWasIg && !Targets.isInstagram(pkg)) endIgSession();
        boolean busy = Overlay.busy();

        if (blocked && Targets.isBlockedApp(pkg)) { if (!busy) enforce(pkg, null); return 5000; }
        if (social && Targets.isSocialApp(pkg)) { if (!busy) enforce(pkg, Phrases.SOCIAL.next()); return 5000; }

        if (Targets.isInstagram(pkg)) {
            if (busy) return 1000;
            long now = Util.meterNow(this);
            UsageMeter m = Util.meter(this);
            UsageMeter.Verdict v = m.sample(now, true);
            if (v == UsageMeter.Verdict.BLOCK) {
                lastWasIg = false;
                Util.saveMeter(this);
                enforce(pkg, "Back in " + m.lockoutMinutes(now) + " min.");
            } else {
                lastWasIg = true;
                if (++igSamples % 15 == 0) Util.saveMeter(this);
            }
            return 1000;
        }

        if (Targets.isBrowser(pkg)) {
            String url = readUrl(root, pkg);
            Diag.onBrowserRead(pkg, url != null);
            if (!busy && Adult.isAdult(url)) closeAdult(pkg, Adult.hostOf(url));
            return 700;
        }

        if (tamper && Targets.isTamperHost(pkg)) {
            boolean hit = scan(root, new int[]{600}, 0);
            if (Targets.decide(pkg, blocked, tamper, hit) == Targets.Decision.BLOCK && !busy) enforce(pkg, null);
            return 1000;
        }
        return idle;
    }

    private void endIgSession() {
        if (!lastWasIg) return;
        lastWasIg = false;
        try {
            Util.meter(this).sample(Util.meterNow(this), false);
            Util.saveMeter(this);
        } catch (Throwable ignored) {}
    }

    // ---------- adult sites: silent. Black screen, close the TAB through Chrome's own UI, then Home, then kill ----------
    private void closeAdult(final String pkg, final String host) {
        Overlay.cover(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 5000, new Overlay.Work() {
            @Override public void run(Runnable done) { new Closer(pkg, host, done).next(); }
        });
    }

    /** Tries the most precise way first and checks after each: long-press tab button, menu, tab grid, Back. */
    private final class Closer {
        final String pkg, host; final Runnable done; int step;
        Closer(String pkg, String host, Runnable done) { this.pkg = pkg; this.host = host; this.done = done; }

        void next() {
            switch (step++) {
                case 0: longPress(); break;
                case 1: mainMenu(); break;
                case 2: tabGrid(); break;
                case 3: back(); break;
                default: finish("Left without closing the tab");
            }
        }

        void verify(long delay, final String how) {
            h.postDelayed(new Runnable() {
                @Override public void run() { if (stillAdult()) next(); else finish(how); }
            }, delay);
        }

        void longPress() {
            AccessibilityNodeInfo btn = findById("tab_switcher_button");
            if (btn == null) btn = findByLabel(new Predicate<String>() { public boolean test(String s) { return TabClose.isTabSwitcherLabel(s); } });
            if (btn == null || !act(btn, true)) { next(); return; }
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (clickLabel(new Predicate<String>() { public boolean test(String s) { return TabClose.isCloseTabItem(s); } })) verify(350, "Tab closed (long-press menu)");
                    else { performGlobalAction(GLOBAL_ACTION_BACK); next(); }
                }
            }, 380);
        }

        void mainMenu() {
            AccessibilityNodeInfo mb = findById("menu_button");
            if (mb == null || !act(mb, false)) { next(); return; }
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (clickLabel(new Predicate<String>() { public boolean test(String s) { return TabClose.isCloseTabItem(s); } })) verify(350, "Tab closed (menu)");
                    else { performGlobalAction(GLOBAL_ACTION_BACK); next(); }
                }
            }, 420);
        }

        void tabGrid() {
            AccessibilityNodeInfo btn = findById("tab_switcher_button");
            if (btn == null) btn = findByLabel(new Predicate<String>() { public boolean test(String s) { return TabClose.isTabSwitcherLabel(s); } });
            if (btn == null || !act(btn, false)) { next(); return; }
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    final List<AccessibilityNodeInfo> cards = new ArrayList<>();
                    final List<String> labels = new ArrayList<>();
                    for (AccessibilityNodeInfo r : roots()) {
                        walk(r, 500, new Predicate<AccessibilityNodeInfo>() {
                            public boolean test(AccessibilityNodeInfo n) {
                                String d = labelOf(n);
                                if (TabClose.isCardCloseLabel(d)) { cards.add(n); labels.add(d); }
                                return false;
                            }
                        });
                    }
                    int i = TabClose.pickCard(labels, host);
                    if (i >= 0 && act(cards.get(i), false)) verify(450, "Tab closed (tab switcher)");
                    else { performGlobalAction(GLOBAL_ACTION_BACK); next(); }
                }
            }, 520);
        }

        void back() {
            performGlobalAction(GLOBAL_ACTION_BACK);
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    if (stillAdult()) { performGlobalAction(GLOBAL_ACTION_BACK); verify(250, "Went back"); }
                    else finish("Went back");
                }
            }, 250);
        }

        void finish(String how) {
            Diag.lastAction = how;
            performGlobalAction(GLOBAL_ACTION_HOME);
            h.postDelayed(new Runnable() { @Override public void run() { Overlay.killApp(GuardService.this, pkg); } }, 200);
            h.postDelayed(done, 350);
        }
    }

    private boolean stillAdult() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            String p = (r != null && r.getPackageName() != null) ? r.getPackageName().toString() : null;
            return p != null && Targets.isBrowser(p) && Adult.isAdult(readUrl(r, p));
        } catch (Throwable t) { return false; }
    }

    // ---------- reading and clicking other apps' UI ----------
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        try {
            AccessibilityNodeInfo a = getRootInActiveWindow();
            if (a != null) out.add(a);
            List<android.view.accessibility.AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (android.view.accessibility.AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) out.add(r);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String labelOf(AccessibilityNodeInfo n) {
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) return d.toString();
        CharSequence t = n.getText();
        return t == null ? null : t.toString();
    }

    private static boolean walk(AccessibilityNodeInfo root, int budget, Predicate<AccessibilityNodeInfo> visit) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty() && budget-- > 0) {
            AccessibilityNodeInfo n = q.poll();
            if (visit.test(n)) return true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findById(String idSuffix) {
        for (AccessibilityNodeInfo r : roots()) {
            try {
                CharSequence p = r.getPackageName();
                if (p == null) continue;
                List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(p + ":id/" + idSuffix);
                if (l != null && !l.isEmpty()) return l.get(0);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private AccessibilityNodeInfo findByLabel(final Predicate<String> match) {
        final AccessibilityNodeInfo[] hit = {null};
        for (AccessibilityNodeInfo r : roots()) {
            if (walk(r, 500, new Predicate<AccessibilityNodeInfo>() {
                public boolean test(AccessibilityNodeInfo n) {
                    String d = labelOf(n);
                    if (d != null && match.test(d)) { hit[0] = n; return true; }
                    CharSequence t = n.getText();
                    if (t != null && match.test(t.toString())) { hit[0] = n; return true; }
                    return false;
                }
            })) break;
        }
        return hit[0];
    }

    private boolean clickLabel(Predicate<String> match) {
        AccessibilityNodeInfo n = findByLabel(match);
        return n != null && act(n, false);
    }

    /** Click (or long-click) the node, or the nearest ancestor that accepts it. */
    private static boolean act(AccessibilityNodeInfo node, boolean longClick) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; n != null && i < 6; i++) {
            if (longClick ? n.isLongClickable() : n.isClickable()) {
                return n.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
            }
            n = n.getParent();
        }
        return node.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
    }

    /** The address bar text: known ids first (Chrome answers on the first), then a bounded scan near the top of the screen. */
    private static String readUrl(AccessibilityNodeInfo root, String pkg) {
        if (root == null) return null;
        for (String id : Targets.URL_BAR_IDS) {
            try {
                List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
                if (l != null && !l.isEmpty() && l.get(0).getText() != null) return l.get(0).getText().toString();
            } catch (Throwable ignored) {}
        }
        final String[] found = {null};
        try {
            walk(root, 160, new Predicate<AccessibilityNodeInfo>() {
                public boolean test(AccessibilityNodeInfo n) {
                    String vid = n.getViewIdResourceName();
                    CharSequence t = n.getText();
                    if (t == null || t.length() == 0) return false;
                    boolean urlish = vid != null && vid.toLowerCase(java.util.Locale.ROOT).contains("url");
                    boolean edit = "android.widget.EditText".contentEquals(n.getClassName() == null ? "" : n.getClassName())
                            && Adult.hostOf(t) != null;
                    if (urlish || edit) { found[0] = t.toString(); return true; }
                    return false;
                }
            });
        } catch (Throwable ignored) {}
        return found[0];
    }

    void kick() {
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private boolean interactive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void enforce(String pkg, String line) {
        Overlay.show(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, pkg, new Runnable() {
            @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); }
        }, line);
    }

    private static boolean scan(AccessibilityNodeInfo n, int[] budget, int depth) {
        if (n == null || budget[0]-- <= 0 || depth > 30) return false;
        if (Targets.matchesTamperText(n.getText()) || Targets.matchesTamperText(n.getContentDescription())) return true;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null && scan(c, budget, depth + 1)) return true;
        }
        return false;
    }

    @Override public void onInterrupt() {}

    private void shutDown() {
        alive = false;
        instance = null;
        endIgSession();
        h.removeCallbacksAndMessages(null);
        if (rxOn) { try { unregisterReceiver(screenRx); } catch (Throwable ignored) {} rxOn = false; }
    }

    @Override public boolean onUnbind(Intent intent) {
        shutDown();
        Util.armAlarm(this, 3000);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        shutDown();
        super.onDestroy();
    }
}
