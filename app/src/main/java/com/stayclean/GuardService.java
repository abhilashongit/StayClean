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

    private static final long NIGHT_IDLE_MS = 15_000;        // safety re-check at night; events do the real work
    private static final long IG_POLL_MS = 3_000;            // Instagram meter sampling
    private static final long BROWSER_FAST_POLL_MS = 700, BROWSER_SLOW_POLL_MS = 1_500, BROWSER_FAST_MS = 10_000;
    private static final long REDDIT_FAST_POLL_MS = 1_000, REDDIT_SLOW_POLL_MS = 2_000;

    private final Handler h = new Handler(Looper.getMainLooper());
    private Schedule.Phase applied;
    private volatile String lastPkg;
    private boolean rxOn;
    private Social.Svc meterSvc;                              // Instagram or X being timed right now (app or website)
    private int igSamples;
    private String lastUrl;
    private long fastUntil;        // browsers: poll quickly until this time, then relax

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
        @Override public void onReceive(Context c, Intent i) {
            if (i != null && Intent.ACTION_USER_PRESENT.equals(i.getAction())) Morning.onUnlock(GuardService.this);
            kick();
        }
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
        if (BankGuard.isPaused(this)) { BankGuard.clearPaused(this); WatchdogService.cancelResume(this); }
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
                    i.packageNames = (p == Schedule.Phase.DAY && !BankGuard.enabled(this)) ? Targets.dayFilter() : null;
                    setServiceInfo(i);
                }
                applied = p;
            } catch (Throwable ignored) {}
        }
        kick();
    }

    /** Re-evaluate the event filter now (e.g. the Bank Pass toggle changed). */
    static void refilter() {
        GuardService g = instance;
        if (alive && g != null) { Schedule.Phase p = g.applied; g.applied = null; g.applyPhase(p == null ? Util.phaseNow(g) : p); }
    }

    /** A bank/UPI app is open: record it, keep the watchdog awake to notice when you leave, then switch off. */
    private void pauseForBank(String pkg) {
        if (BankGuard.isPaused(this)) { disableSelf(); return; }
        BankGuard.markPaused(this, pkg);
        Diag.bankPauses++;
        Diag.lastAction = "Stepped aside for a bank app";
        try {
            Intent i = new Intent(this, WatchdogService.class).setAction(WatchdogService.ACTION_BANK_WATCH);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Throwable ignored) {}
        disableSelf();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        if (BankGuard.enabled(this) && BankGuard.isBank(this, pkg)) { pauseForBank(pkg); return; }
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
        if (Targets.isBrowser(pkg) || RedditGuard.isRedditApp(pkg)) {
            fastUntil = android.os.SystemClock.uptimeMillis() + BROWSER_FAST_MS; kick(); return;
        }
        if (Social.ofApp(pkg) != null) { kick(); return; }
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
        boolean watch = blocked || social || meterSvc != null || Social.ofApp(lp) != null || Targets.isBrowser(lp) || RedditGuard.isRedditApp(lp)
                || (tamper && Targets.isTamperHost(lp));
        if (!watch) return -1;
        long idle = (blocked || social) ? NIGHT_IDLE_MS : -1;

        // Screen off: stop entirely. The SCREEN_ON / USER_PRESENT receiver wakes us the moment it matters.
        if (!interactive()) { endMeterSession(); return -1; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = (root != null && root.getPackageName() != null) ? root.getPackageName().toString() : null;
        if (pkg == null || pkg.equals(getPackageName())) {
            if (Targets.isBrowser(lp)) return BROWSER_FAST_POLL_MS;   // keep polling through transient empty screens
            if (RedditGuard.isRedditApp(lp)) return REDDIT_SLOW_POLL_MS;
            return meterSvc != null ? IG_POLL_MS : idle;
        }
        lastPkg = pkg;
        if (meterSvc != null && !Targets.isBrowser(pkg) && Social.ofApp(pkg) != meterSvc) endMeterSession();
        boolean busy = Overlay.busy();

        if (blocked && Targets.isBlockedApp(pkg)) { if (!busy) enforce(pkg, null); return NIGHT_IDLE_MS; }
        if (social && Targets.isSocialApp(pkg)) { if (!busy) enforce(pkg, Phrases.SOCIAL.next()); return NIGHT_IDLE_MS; }

        Social.Svc app = Social.ofApp(pkg);
        if (app != null) {
            if (busy) return IG_POLL_MS;
            long wait = meterTick(app);
            if (wait < 0) { enforce(pkg, lockLine(app)); return IG_POLL_MS; }
            return wait;
        }

        if (Targets.isBrowser(pkg)) {
            String url = readUrl(root, pkg);
            Diag.onBrowserRead(pkg, url != null);
            long up = android.os.SystemClock.uptimeMillis();
            if (url != null && !url.equals(lastUrl)) { lastUrl = url; fastUntil = up + BROWSER_FAST_MS; }
            long poll = up < fastUntil ? BROWSER_FAST_POLL_MS : BROWSER_SLOW_POLL_MS;
            Social.Svc web = Social.ofUrl(url);
            if (url != null && meterSvc != null && web != meterSvc) endMeterSession();   // left the site (or switched)
            if (!busy && Adult.isAdult(url)) closeAdult(pkg, Adult.hostOf(url));
            else if (!busy && redditUrlBlocked(url)) closeAdult(pkg, Adult.hostOf(url));
            else if (web != null && !busy) {
                // instagram.com / x.com: closed at night, and timed into the same allowance as the app by day
                if (social) { Diag.lastAction = web.label + " website: night lock"; closeAdult(pkg, Adult.hostOf(url)); return poll; }
                long wait = meterTick(web);
                if (wait < 0) { Diag.lastAction = web.label + " website: time used"; closeAdult(pkg, Adult.hostOf(url)); return poll; }
                return Math.min(poll, wait);
            }
            // Fast right after the page or tab changes, relaxed while you read one page.
            return poll;
        }

        if (RedditGuard.isRedditApp(pkg)) {
            if (!busy) checkReddit(root);
            long up = android.os.SystemClock.uptimeMillis();
            return up < fastUntil ? REDDIT_FAST_POLL_MS : REDDIT_SLOW_POLL_MS;
        }

        if (tamper && Targets.isTamperHost(pkg)) {
            int found = scan(root, new int[]{600}, 0, Targets.isInstaller(pkg));
            if (Targets.decide(pkg, blocked, tamper, (found & HIT_TAMPER) != 0, (found & HIT_UNINSTALL) != 0)
                    == Targets.Decision.BLOCK && !busy) enforce(pkg, null);
            return 1000;
        }
        return idle;
    }

    /** One foreground sample for svc. Returns ms until the next sample, or -1 when its time is used up. */
    private long meterTick(Social.Svc svc) {
        if (meterSvc != null && meterSvc != svc) endMeterSession();
        long now = Util.meterNow(this);
        UsageMeter m = Util.meter(this, svc);
        if (m.sample(now, true) == UsageMeter.Verdict.BLOCK) {
            meterSvc = null;
            Util.saveMeter(this, svc);
            return -1;
        }
        meterSvc = svc;
        if (++igSamples % 5 == 0) Util.saveMeter(this, svc);
        // Relaxed sampling, but land right on the limit instead of overshooting it.
        return Math.max(250, Math.min(IG_POLL_MS, m.remainingMs() + 50));
    }

    private String lockLine(Social.Svc svc) {
        UsageMeter m = Util.meter(this, svc);
        return m.allowance() <= 0 ? "You set this to zero." : "Back in " + m.lockoutMinutes(Util.meterNow(this)) + " min.";
    }

    private void endMeterSession() {
        Social.Svc svc = meterSvc;
        if (svc == null) return;
        meterSvc = null;
        try {
            Util.meter(this, svc).sample(Util.meterNow(this), false);
            Util.saveMeter(this, svc);
        } catch (Throwable ignored) {}
    }

    /** instagram.com or x.com while it should be closed (night lock, or its time is used up). */
    private boolean socialUrlBlocked(String url) {
        Social.Svc svc = Social.ofUrl(url);
        if (svc == null) return false;
        return Util.socialNightNow(this) || Util.meter(this, svc).lockedAt(Util.meterNow(this));
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
            if (p == null || !Targets.isBrowser(p)) return false;
            String u = readUrl(r, p);
            return Adult.isAdult(u) || redditUrlBlocked(u) || socialUrlBlocked(u);
        } catch (Throwable t) { return false; }
    }

    // ---------- Reddit: adult communities only (never single posts' tags, profiles or feeds) ----------
    private void ensureRedditList() {
        if (RedditGuard.listSize() >= 0) return;
        try { RedditGuard.load(getResources().openRawResource(R.raw.reddit18)); }
        catch (Throwable t) { RedditGuard.load(new java.io.ByteArrayInputStream(new byte[0])); }   // rules still work
    }

    private boolean redditUrlBlocked(String url) {
        if (url == null || !url.toLowerCase(java.util.Locale.ROOT).contains("reddit.com")) return false;
        ensureRedditList();
        return RedditGuard.isBlockedUrl(url);
    }

    private static final String SEARCH = "\u0000search";

    /** What to close in the Reddit app right now: an adult community's name, SEARCH for an adult search, or null. */
    private String redditHit(AccessibilityNodeInfo root, boolean record) {
        if (root == null) return null;
        ensureRedditList();
        android.graphics.Rect b = new android.graphics.Rect();
        root.getBoundsInScreen(b);
        int h = Math.max(1, b.height() > 0 ? b.bottom : getResources().getDisplayMetrics().heightPixels);
        RedditGuard.Screen s = new RedditGuard.Screen();
        scanReddit(root, s, h, new int[]{250}, 0, b);
        if (record) Diag.redditScreen(android.text.TextUtils.join(" \u00b7 ", s.probe));
        String name = s.openCommunity();
        if (name != null && record) Diag.redditPages++;
        if (name != null && RedditGuard.isAdultCommunity(name)) return name;
        return s.adultSearch() ? SEARCH : null;
    }

    /** Walks only the top of the screen (headers, buttons, Reddit's own 18+ gate), within a node budget. */
    private static void scanReddit(AccessibilityNodeInfo n, RedditGuard.Screen s, int h, int[] budget, int depth, android.graphics.Rect b) {
        if (n == null || budget[0]-- <= 0 || depth > 30) return;
        n.getBoundsInScreen(b);
        if (depth > 2 && b.top > h * 0.6f) return;               // below the fold: feeds and comments live there
        float y = b.top / (float) h;
        s.add(n.getText(), y);
        s.add(n.getContentDescription(), y);
        if (n.isEditable() && y < 0.3f) s.field(n.getText(), n.isFocused());
        s.probe(n.getText(), y);
        s.probe(n.getContentDescription(), y);
        String vid = n.getViewIdResourceName();
        if (vid != null) { int k = vid.indexOf(":id/"); s.probe("#" + (k >= 0 ? vid.substring(k + 4) : vid), y); }
        for (int i = 0; i < n.getChildCount() && budget[0] > 0; i++) scanReddit(n.getChild(i), s, h, budget, depth + 1, b);
    }

    private String redditLast; private long redditLastAt; private final ArrayDeque<Long> redditActs = new ArrayDeque<>();

    private void checkReddit(AccessibilityNodeInfo root) {
        Diag.redditScans++;
        final String name = redditHit(root, true);
        if (name == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (name.equals(redditLast) && now - redditLastAt < 4000) return;     // one action per community per 4 s
        redditLast = name; redditLastAt = now;
        while (!redditActs.isEmpty() && now - redditActs.peekFirst() > 20_000) redditActs.pollFirst();
        redditActs.addLast(now);
        final boolean loop = redditActs.size() >= 3;                          // Back is not getting out: go Home
        if (name.equals(SEARCH)) Diag.redditSearches++; else Diag.redditClosed++;
        Overlay.cover(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 4000, new Overlay.Work() {
            @Override public void run(final Runnable done) {
                if (loop) { performGlobalAction(GLOBAL_ACTION_HOME); Diag.lastAction = "Reddit: went home"; h.postDelayed(done, 350); return; }
                performGlobalAction(GLOBAL_ACTION_BACK);
                h.postDelayed(new Runnable() { @Override public void run() {
                    if (!stillReddit(name)) { Diag.lastAction = "Reddit: stepped back"; done.run(); return; }
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    h.postDelayed(new Runnable() { @Override public void run() {
                        if (stillReddit(name)) { performGlobalAction(GLOBAL_ACTION_HOME); Diag.lastAction = "Reddit: went home"; }
                        else Diag.lastAction = "Reddit: stepped back";
                        h.postDelayed(done, 250);
                    }}, 700);
                }}, 700);
            }
        });
    }

    private boolean stillReddit(String name) {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null || r.getPackageName() == null || !RedditGuard.isRedditApp(r.getPackageName().toString())) return false;
            return name.equals(redditHit(r, false));
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

    private static final int HIT_TAMPER = 1, HIT_UNINSTALL = 2;

    /** Bit mask of what the page shows. In the installer we also look for uninstall wording. */
    private static int scan(AccessibilityNodeInfo n, int[] budget, int depth, boolean installer) {
        if (n == null || budget[0]-- <= 0 || depth > 30) return 0;
        CharSequence t = n.getText(), d = n.getContentDescription();
        int m = 0;
        if (Targets.matchesTamperText(t) || Targets.matchesTamperText(d)) m |= HIT_TAMPER;
        if (installer && (Targets.matchesUninstallText(t) || Targets.matchesUninstallText(d))) m |= HIT_UNINSTALL;
        int want = installer ? (HIT_TAMPER | HIT_UNINSTALL) : HIT_TAMPER;
        for (int i = 0; i < n.getChildCount() && (m & want) != want; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) m |= scan(c, budget, depth + 1, installer);
        }
        return m;
    }

    @Override public void onInterrupt() {}

    private void shutDown() {
        alive = false;
        instance = null;
        endMeterSession();
        h.removeCallbacksAndMessages(null);
        if (rxOn) { try { unregisterReceiver(screenRx); } catch (Throwable ignored) {} rxOn = false; }
    }

    @Override public boolean onUnbind(Intent intent) {
        shutDown();
        WatchdogService.poke();                       // the backup takes over at once
        Util.armAlarm(this, 3000);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        shutDown();
        super.onDestroy();
    }
}
