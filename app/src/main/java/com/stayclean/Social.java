package com.stayclean;

import java.util.Locale;

/** Instagram and X: which service an app or a web address belongs to. Each has its own limit and its own meter. */
public final class Social {
    private Social() {}

    public enum Svc {
        IG("Instagram", "meter", "limit_ms", "limit_day"),
        X("X", "meter_x", "x_limit_ms", "x_limit_day");

        public final String label;
        final String meterPrefs, limitKey, dayKey;
        Svc(String label, String meterPrefs, String limitKey, String dayKey) {
            this.label = label; this.meterPrefs = meterPrefs; this.limitKey = limitKey; this.dayKey = dayKey;
        }
    }

    public static Svc ofApp(String pkg) {
        if (Targets.INSTAGRAM.equals(pkg)) return Svc.IG;
        if (Targets.X_APP.equals(pkg)) return Svc.X;
        return null;
    }

    /** instagram.com, x.com, twitter.com and their subdomains (www, m, mobile). Anything else is null. */
    public static Svc ofUrl(CharSequence url) {
        String h = Adult.hostOf(url);
        if (h == null) return null;
        h = h.toLowerCase(Locale.ROOT);
        if (is(h, "instagram.com")) return Svc.IG;
        if (is(h, "x.com") || is(h, "twitter.com")) return Svc.X;
        return null;
    }

    private static boolean is(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
}
