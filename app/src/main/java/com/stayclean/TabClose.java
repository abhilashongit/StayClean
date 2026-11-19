package com.stayclean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure label matching for closing a browser tab through its own UI (English labels). */
public final class TabClose {
    private TabClose() {}

    /** The menu entry that closes the current tab ("Close tab"). */
    public static boolean isCloseTabItem(String s) {
        return s != null && s.trim().equalsIgnoreCase("close tab");
    }

    /** The toolbar tab-count button: "Switch or close tabs. 3 open tabs". */
    public static boolean isTabSwitcherLabel(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.contains("switch or close tabs") || t.matches(".*\\d+ open tabs?.*");
    }

    /** A tab card's own close button in the grid: "Close <title> tab". Never "Close tab" or "Close all ...". */
    public static boolean isCardCloseLabel(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith("close all") || t.contains(" all tabs") || t.contains("all incognito")) return false;
        return t.length() > 9 && ((t.startsWith("close ") && t.endsWith(" tab")) || t.startsWith("close tab "));
    }

    private static final Set<String> NOISE = new HashSet<>(Arrays.asList("www", "com", "net", "org", "html", "mobile", "m"));

    /** Brand-like words from a host: "www.xhamster.com" -> ["xhamster"]. */
    public static List<String> hostHints(String host) {
        List<String> out = new ArrayList<>();
        if (host == null) return out;
        for (String part : host.toLowerCase(Locale.ROOT).split("[.\\-]")) {
            if (part.length() >= 4 && !NOISE.contains(part)) out.add(part);
        }
        return out;
    }

    /**
     * Which card to close in the tab grid. A card whose title mentions the site wins;
     * with exactly one card there is no doubt; otherwise -1 (do not guess and close a good tab).
     */
    public static int pickCard(List<String> closeLabels, String host) {
        List<String> hints = hostHints(host);
        int valid = 0, only = -1;
        for (int i = 0; i < closeLabels.size(); i++) {
            String d = closeLabels.get(i);
            if (!isCardCloseLabel(d)) continue;
            valid++; only = i;
            String l = d.toLowerCase(Locale.ROOT);
            for (String h : hints) if (l.contains(h)) return i;
        }
        return valid == 1 ? only : -1;
    }
}
