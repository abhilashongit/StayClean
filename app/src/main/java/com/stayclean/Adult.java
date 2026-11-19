package com.stayclean;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether an address-bar string is an adult website. Hosts only (paths and search queries are ignored).
 * Deliberately token-based so ordinary words that merely contain a letter run (essex, analysis, ...) never match.
 */
public final class Adult {
    private Adult() {}

    /** Any label of the host that is exactly one of these. */
    private static final Set<String> LABELS = new HashSet<>(Arrays.asList(
            "xxx", "sex", "sexy", "nsfw", "milf", "camgirl", "camgirls", "beeg", "txxx", "hclips", "daftsex", "xtube",
            "erome", "fapello", "thothub", "cam4", "camsoda", "myfreecams", "flirt4free", "literotica", "hotmovies",
            "tubegalore", "fansly", "onlyfans", "nhentai", "e-hentai", "rule34", "gelbooru", "motherless", "eporner",
            "tnaflix", "tube8", "redtube", "youporn", "xnxx", "xvideos", "xhamster", "spankbang", "chaturbate",
            "stripchat", "bongacams", "livejasmin", "brazzers", "bangbros", "sunporno", "hqporn"));

    /** Any label that STARTS with one of these (regional / numbered mirrors: xhamster2, xvideos-india, ...). */
    private static final String[] LABEL_PREFIXES = {
            "porn", "xvideos", "xnxx", "xhamster", "hentai", "redtube", "youporn", "spankbang", "chaturbate",
            "stripchat", "bongacams", "livejasmin", "brazzers", "bangbros", "onlyfans", "nhentai", "eporner", "tnaflix"};

    /** Whole-label substrings that are unmistakable on their own. */
    private static final String[] LABEL_CONTAINS = {"porn", "hentai", "xvideos", "xnxx", "xhamster"};

    private static final Set<String> TLDS = new HashSet<>(Arrays.asList("xxx", "porn", "adult", "sex", "sexy"));

    /** True if the text looks like a URL/host that belongs to an adult site. Search queries (with spaces) are never blocked. */
    public static boolean isAdult(CharSequence text) {
        String host = hostOf(text);
        if (host == null) return false;
        String[] labels = host.split("\\.");
        if (labels.length < 2) return false;
        if (TLDS.contains(labels[labels.length - 1])) return true;
        for (String label : labels) {
            if (label.isEmpty()) continue;
            if (LABELS.contains(label)) return true;
            for (String p : LABEL_PREFIXES) if (label.startsWith(p)) return true;
            for (String c : LABEL_CONTAINS) if (label.contains(c)) return true;
            for (String tok : label.split("-")) {
                if (LABELS.contains(tok) && tok.length() >= 3 && !tok.equals("cam4")) return true;
            }
        }
        return false;
    }

    static String hostOf(CharSequence text) {
        if (text == null) return null;
        String s = text.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > 300) return null;
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return null;   // a search query
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#', ':'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        if (s.startsWith(".")) s = s.substring(1);
        return s.contains(".") ? s : null;
    }
}
