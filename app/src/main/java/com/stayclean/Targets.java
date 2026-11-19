package com.stayclean;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure decision rules: what is blocked, and what counts as tampering. */
public final class Targets {
    private Targets() {}

    /** Exact package names. Add more here to block more apps. */
    public static final Set<String> BLOCKED_APPS = new HashSet<>(Arrays.asList(
            "in.swiggy.android",          // Swiggy (food, Instamart, Genie)
            "com.application.zomato"      // Zomato
    ));

    public static final String INSTAGRAM = "com.instagram.android";
    public static final String X_APP = "com.twitter.android";
    /** Night lock (23:00 to 05:00) when the toggle is on. */
    public static final Set<String> SOCIAL_NIGHT = new HashSet<>(Arrays.asList(INSTAGRAM, X_APP));

    /** Browsers whose address bar we read. Chrome family first; others are best-effort. */
    public static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
            "com.opera.browser", "com.duckduckgo.mobile.android", "com.vivaldi.browser"));

    /** Address-bar view ids to try, as resource-id suffixes after "<package>:id/". */
    public static final String[] URL_BAR_IDS = {"url_bar", "location_bar_edit_text", "mozac_browser_toolbar_url_view", "url_bar_title"};

    /** What the accessibility service listens to by day: only these packages. */
    public static String[] dayFilter() {
        Set<String> all = new java.util.TreeSet<>();
        all.addAll(BLOCKED_APPS); all.addAll(SOCIAL_NIGHT); all.addAll(BROWSERS);
        return all.toArray(new String[0]);
    }

    public static boolean isSocialApp(String pkg) { return pkg != null && SOCIAL_NIGHT.contains(pkg); }
    public static boolean isInstagram(String pkg) { return INSTAGRAM.equals(pkg); }
    public static boolean isBrowser(String pkg) { return pkg != null && BROWSERS.contains(pkg); }
    public static boolean isKillable(String pkg) { return isBlockedApp(pkg) || isSocialApp(pkg) || isBrowser(pkg); }

    /** Night lock rule. */
    public static boolean socialBlocked(String pkg, boolean toggleOn, boolean windowNow) {
        return toggleOn && windowNow && isSocialApp(pkg);
    }

    /** Screens that can switch this guard off live in these packages (stock + common OEM). */
    private static final String[] TAMPER_HOST_HINTS = {
            "settings", "packageinstaller", "permissioncontroller", "securitycenter",
            "safecenter", "systemmanager", "appmanager"
    };

    /** Text that appears on pages where this app (or the clock) can be disabled, removed or tampered with. */
    private static final String[] TAMPER_NEEDLES = {
            "stay clean",
            "set time automatically", "use network-provided time", "automatic date and time",
            "automatic date & time", "set time zone automatically", "use network-provided time zone"
    };

    public enum Decision { IGNORE, BLOCK }
    public enum Enforcer { PRIMARY_ACCESSIBILITY, FALLBACK_OVERLAY, FALLBACK_HOME_ONLY, NONE }

    public static boolean isBlockedApp(String pkg) { return pkg != null && BLOCKED_APPS.contains(pkg); }

    public static boolean isTamperHost(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.equals("com.stayclean.guard")) return false;
        for (String h : TAMPER_HOST_HINTS) if (p.contains(h)) return true;
        return false;
    }

    public static boolean matchesTamperText(CharSequence text) {
        if (text == null) return false;
        String t = text.toString().toLowerCase(Locale.ROOT);
        for (String n : TAMPER_NEEDLES) if (t.contains(n)) return true;
        return false;
    }

    /** The system screen that installs, updates and removes apps. */
    public static boolean isInstaller(String pkg) {
        return pkg != null && pkg.toLowerCase(Locale.ROOT).contains("packageinstaller");
    }

    /** Words on an uninstall screen. Install and update screens never say these. */
    public static boolean matchesUninstallText(CharSequence text) {
        if (text == null) return false;
        String t = text.toString().toLowerCase(Locale.ROOT);
        return t.contains("uninstall") || t.contains("remove app");
    }

    /** The single rule that decides whether a window in the foreground must be covered and closed. */
    public static Decision decide(String pkg, boolean blockedNow, boolean tamperLockNow, boolean screenHasTamperText) {
        return decide(pkg, blockedNow, tamperLockNow, screenHasTamperText, true);
    }

    /**
     * In the package installer only an uninstall screen is tampering. Installing or updating Stay Clean
     * is always allowed: Android only accepts an update signed with the same key, and never a downgrade.
     */
    public static Decision decide(String pkg, boolean blockedNow, boolean tamperLockNow,
                                  boolean screenHasTamperText, boolean screenHasUninstallText) {
        if (blockedNow && isBlockedApp(pkg)) return Decision.BLOCK;
        if (tamperLockNow && isTamperHost(pkg) && screenHasTamperText
                && (!isInstaller(pkg) || screenHasUninstallText)) return Decision.BLOCK;
        return Decision.IGNORE;
    }

    /** Failover: who enforces right now, given what is alive / permitted. */
    public static Enforcer chooseEnforcer(boolean accessibilityAlive, boolean usageAccess, boolean overlayPermission) {
        if (accessibilityAlive) return Enforcer.PRIMARY_ACCESSIBILITY;
        if (usageAccess && overlayPermission) return Enforcer.FALLBACK_OVERLAY;
        if (usageAccess) return Enforcer.FALLBACK_HOME_ONLY;
        return Enforcer.NONE;
    }
}
