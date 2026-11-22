package com.stayclean;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bank Pass. Banks and UPI apps treat any enabled accessibility service as a threat and refuse to run, so while
 * one is open Stay Clean switches itself off (a service may disable itself, no permission needed) and, once you
 * leave, asks with one tap to switch back on. Detection needs no upkeep: a curated set, Android's own "finance"
 * and a package-name heuristic cover banks we have never seen, including ones not in the list.
 *
 * The name checks are pure (no Context) so they can be unit-tested.
 */
public final class BankGuard {
    private BankGuard() {}

    /** Confirmed packages. The token heuristic below catches the rest, so this need not be exhaustive. */
    static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "money.super.payments",                           // super.money (SuperMoney UPI) — reported
            "com.axis.mobile", "com.axisbank.mobile.oneapp",  // Axis Mobile / Axis ONEApp — reported
            "com.google.android.apps.nbu.paisa.user",         // Google Pay
            "com.phonepe.app", "com.phonepe.business",        // PhonePe
            "net.one97.paytm",                                // Paytm
            "in.org.npci.upiapp",                             // BHIM
            "com.csam.icici.bank.imobile", "com.icicibank.pockets",
            "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", // YONO SBI
            "com.snapwork.hdfc", "com.hdfcbank.payzapp",      // HDFC
            "com.kotak.mobilebanking", "com.msf.kbank.mobile",
            "com.bankofbaroda.mconnect", "com.infrasoft.uboi",
            "com.mobikwik_new", "com.freecharge.android",
            "com.dreamplug.androidapp",                       // CRED
            "com.naviapp", "money.jupiter", "in.tatadigital.tatapay"));

    /** A lower-cased package containing any of these is treated as a bank/finance app. */
    private static final String[] TOKENS = {"bank", "upi", "axis", "hdfc", "icici", "sbi.", "kotak", "yesbank",
            "idfc", "canara", "unionbank", "indusind", "federalbank", "rblbank", "paytm", "phonepe", "bhim",
            "mobikwik", "freecharge", "supermoney", "super.payments", "payzapp", "yono", "imobile", "pockets",
            "jupiter", "fampay", "navi", "groww", "zerodha", "upstox", "npci"};

    /** Never pause for these, even if a token would otherwise match. */
    static boolean excludedByName(String pkg, String ownPkg) {
        return pkg == null || pkg.isEmpty() || pkg.equals(ownPkg)
                || Targets.isBrowser(pkg) || Targets.isSocialApp(pkg)
                || Targets.isBlockedApp(pkg) || RedditGuard.isRedditApp(pkg);
    }

    static boolean nameLooksLikeBank(String pkg) {
        if (KNOWN.contains(pkg)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        for (String t : TOKENS) if (p.contains(t)) return true;
        return false;
    }

    private static final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

    /** Is this foreground package a bank/UPI/finance app we should step aside for? Cached per package. */
    public static boolean isBank(Context c, String pkg) {
        if (excludedByName(pkg, c.getPackageName())) return false;
        Boolean hit = cache.get(pkg);
        if (hit != null) return hit;
        boolean b = nameLooksLikeBank(pkg);
        cache.put(pkg, b);
        return b;
    }

    // ---- settings + pause state (must survive the service being switched off) ----
    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("bank", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return sp(c).getBoolean("on", true); }   // default on
    public static void setEnabled(Context c, boolean on) { sp(c).edit().putBoolean("on", on).apply(); }

    public static boolean isPaused(Context c) { return sp(c).getBoolean("paused", false); }
    public static long pausedSince(Context c) { return sp(c).getLong("since", 0); }
    public static String pausedFor(Context c) { return sp(c).getString("pkg", ""); }

    public static void markPaused(Context c, String pkg) {
        sp(c).edit().putBoolean("paused", true).putLong("since", System.currentTimeMillis())
                .putString("pkg", pkg == null ? "" : pkg).apply();
    }

    public static void clearPaused(Context c) {
        sp(c).edit().putBoolean("paused", false).remove("since").remove("pkg").apply();
    }
}
