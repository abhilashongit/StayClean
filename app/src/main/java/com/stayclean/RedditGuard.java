package com.stayclean;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adult Reddit communities. Judges the COMMUNITY only: never a single post's NSFW tag (spoilers are safe),
 * never profiles, feeds or search.
 *
 * Three checks, in order: an allowlist that always wins, precise name rules, and a packed list of
 * communities Reddit itself flags 18+ (scrambled 32-bit fingerprints, no readable names in the app).
 * The list is built by tools/reddit18 and must stay in step with the rules below.
 */
public final class RedditGuard {
    private RedditGuard() {}

    public static final String APP = "com.reddit.frontpage";

    public static boolean isRedditApp(String pkg) { return APP.equals(pkg); }

    // ---------- rules (mirror tools/reddit18/rules.py) ----------
    private static final String[] TOKENS = {"nsfw", "hentai", "slut", "pussy", "nude", "tits", "milf", "cuck", "rule34", "r34",
            "hotwife", "blowjob", "creampie", "yiff", "titties", "pawg", "whore", "twink", "onlyfans", "xxx", "boobs", "naked",
            "upskirt", "escort", "topless", "braless", "futanari", "lewd"};

    /** Ordinary communities that a rule word would otherwise catch. Always open. */
    private static final Set<String> ALLOW = new HashSet<>(Arrays.asList("anime_titties", "askwomen", "bigdickproblems",
            "churchoffutaba", "classicalartnudes", "hornyjail", "kopiernudeln", "milfhunterkakyoin", "milford",
            "nakedandafraid", "nonnude", "oddlyerotic", "onlyfansadvice", "onlyfansreviews", "pansexmothwhorehouse",
            "petitsbonheurs", "pussypassdenied", "sfw_rule34", "wowthisnsfwsubexists", "xxxtentacion", "xxxtr", "yiffinhell"));

    static boolean byRule(String n) {
        if (n.replace("nsfw", "").contains("sfw")) return false;   // explicitly safe-for-work naming
        for (String t : TOKENS) if (n.contains(t)) return true;
        return false;
    }

    // ---------- the packed list ----------
    private static volatile int[] list;          // fingerprints XOR 0x80000000, sorted (unsigned order as signed)
    static volatile boolean listOk;

    /** Decodes reddit18.bin. On any problem the list stays empty and the rules still work. */
    public static synchronized void load(InputStream in) {
        if (list != null) return;
        int[] out = new int[0];
        try (DataInputStream d = new DataInputStream(in)) {
            byte[] magic = new byte[4];
            d.readFully(magic);
            if (magic[0] != 'S' || magic[1] != 'C' || magic[2] != '1' || magic[3] != '8') throw new IllegalStateException("magic");
            int k = d.readUnsignedByte();
            int n = d.readInt();
            if (k < 1 || k > 31 || n < 0 || n > 200_000) throw new IllegalStateException("header");
            byte[] body = new byte[d.available()];
            d.readFully(body);
            int[] v = new int[n];
            long prev = 0, pos = 0, max = (long) body.length * 8;
            for (int i = 0; i < n; i++) {
                long q = 0;
                while (true) {
                    if (pos >= max) throw new IllegalStateException("truncated");
                    if (((body[(int) (pos >> 3)] >> (7 - (pos & 7))) & 1) == 0) { pos++; break; }
                    q++; pos++;
                }
                long r = 0;
                for (int b = 0; b < k; b++, pos++) {
                    if (pos >= max) throw new IllegalStateException("truncated");
                    r = (r << 1) | ((body[(int) (pos >> 3)] >> (7 - (pos & 7))) & 1);
                }
                prev += (q << k) | r;
                if (prev > 0xFFFFFFFFL) throw new IllegalStateException("range");
                v[i] = (int) prev ^ 0x80000000;
            }
            out = v;
            listOk = true;
        } catch (Throwable t) {
            listOk = false;
        }
        list = out;
    }

    static int listSize() { int[] l = list; return l == null ? -1 : l.length; }

    static int fingerprint(String name) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        } catch (Exception e) { return 0; }
    }

    private static boolean inList(String n) {
        int[] l = list;
        return l != null && l.length > 0 && Arrays.binarySearch(l, fingerprint(n) ^ 0x80000000) >= 0;
    }

    // ---------- names ----------
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{2,21}");

    /** "r/Name", "Name" -> "name"; null if it is not a community name (or is a profile, u_...). */
    static String normalize(String raw) {
        if (raw == null) return null;
        String n = raw.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("r/")) n = n.substring(2);
        if (!NAME.matcher(n).matches() || n.startsWith("u_")) return null;
        return n;
    }

    /** The single rule: is this community adult? */
    public static boolean isAdultCommunity(String raw) {
        String n = normalize(raw);
        if (n == null || ALLOW.contains(n)) return false;
        return byRule(n) || inList(n);
    }

    // ---------- browsers ----------
    /** Community names in a Reddit address: /r/a, /r/a+b, old Reddit's /over18?dest=... Empty if not Reddit. */
    static List<String> communitiesInUrl(CharSequence text) {
        List<String> out = new ArrayList<>();
        String host = Adult.hostOf(text);
        if (host == null || !(host.equals("reddit.com") || host.endsWith(".reddit.com"))) return out;
        String s = text.toString().trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash < 0) return out;
        String path = s.substring(slash);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.startsWith("/over18")) {
            int q = lower.indexOf("dest=");
            if (q < 0) return out;
            String dest = path.substring(q + 5);
            int amp = dest.indexOf('&');
            if (amp >= 0) dest = dest.substring(0, amp);
            try { dest = URLDecoder.decode(dest, "UTF-8"); } catch (Exception e) { return out; }
            return communitiesInUrl(dest.contains("://") ? dest : "reddit.com" + (dest.startsWith("/") ? "" : "/") + dest);
        }
        if (!lower.startsWith("/r/")) return out;            // home, /user/, /u/, /search, /m/ ... ignored
        String seg = path.substring(3);
        int end = seg.length();
        for (char c : new char[]{'/', '?', '#'}) { int i = seg.indexOf(c); if (i >= 0 && i < end) end = i; }
        for (String part : seg.substring(0, end).split("\\+")) {
            String n = normalize(part);
            if (n != null) out.add(n);
        }
        return out;
    }

    /** True if a browser address opens an adult community, or a post inside one. */
    public static boolean isBlockedUrl(CharSequence url) {
        for (String n : communitiesInUrl(url)) if (isAdultCommunity(n)) return true;
        return false;
    }

    // ---------- Reddit app ----------
    private static final Pattern IN_TEXT = Pattern.compile("(?<![A-Za-z0-9_/])r/([A-Za-z0-9_]{2,21})(?![A-Za-z0-9_])");

    /** What one pass over the top of a Reddit app screen found. */
    public static final class Screen {
        final Set<String> names = new HashSet<>();   // community names on short labels
        float topmost = 1f;                          // smallest y (0..1 of screen height) where a name sat
        boolean joinCue, statsCue, gateCue;

        /** Feed one label: its text and how far down the screen its top edge is (0 = top). */
        public void add(CharSequence text, float y) {
            if (text == null) return;
            String s = text.toString();
            if (s.length() <= 40) {                  // headers and labels, not post bodies or comments
                Matcher m = IN_TEXT.matcher(s);
                while (m.find()) {
                    String n = normalize(m.group(1));
                    if (n != null) { names.add(n); topmost = Math.min(topmost, y); }
                }
            }
            String t = IN_TEXT.matcher(s).replaceAll(" ").trim().toLowerCase(Locale.ROOT);   // cues never come from a name
            if (t.equals("join") || t.equals("joined")) joinCue = true;
            if (t.contains("members") || t.contains("weekly visitors") || t.contains("weekly contributions")) statsCue = true;
            if (t.contains("18+") || t.contains("nsfw") || t.contains("mature content") || t.contains("over 18")) gateCue = true;
        }

        /**
         * The community whose page (or post page) is open, or null. Strict on purpose: exactly one community
         * named near the top, plus a page cue. Feeds show many names and are left alone.
         */
        public String openCommunity() {
            if (names.size() != 1) return null;
            if (topmost > 0.4f) return null;
            if (!(joinCue || statsCue || gateCue)) return null;
            return names.iterator().next();
        }
    }
}
