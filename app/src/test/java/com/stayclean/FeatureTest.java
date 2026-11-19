package com.stayclean;

import static org.junit.Assert.*;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.Test;

public class FeatureTest {
    static long ist(int h, int mi, int s) { return BackTest.ist(2026, 11, 20, h, mi, s, 0); }

    // ================= adult-site matcher =================
    @Test public void adult_hosts_are_caught_in_every_form_the_address_bar_shows() {
        String[] bad = {
                "pornhub.com", "www.pornhub.com", "https://www.pornhub.com/view_video.php?viewkey=abc", "m.xvideos.com",
                "https://xnxx.com/video-1", "xhamster2.com/x", "xhamster.desi", "sex.com", "free-xxx-videos.net",
                "https://onlyfans.com/someone", "hentaihaven.xxx", "anything.porn", "site.adult", "http://user@pornhub.com/",
                "WWW.REDTUBE.COM", "spankbang.com/", "in.xvideos-india.com", "xvideos7.com", "chaturbate.com/room",
                "nhentai.net/g/1", "e-hentai.org", "rule34.xxx", "stripchat.com", "the-porn-site.org", "beeg.com", "cam4.com"};
        for (String b : bad) assertTrue(b, Adult.isAdult(b));
    }

    @Test public void ordinary_sites_and_lookalike_words_are_never_caught() {
        String[] good = {
                "google.com", "https://www.google.com/search?q=porn", "essex.ac.uk", "sussex.ac.uk", "middlesex.edu",
                "analysis.com", "scunthorpe.co.uk", "bbc.com/news/sex-education", "en.wikipedia.org/wiki/Sex", "swiggy.com",
                "zomato.com", "instagram.com", "x.com", "github.com", "corn.com", "popcorn.com", "sporting.in", "coxxx.org",
                "localhost", "192.168.0.1", "", "  ", "a", "stay clean", "porn site", "how to stop watching porn",
                "pornography problems help", "https://example.com/p?x=sex#xxx", "unsex.org", "excel.microsoft.com",
                "chrome://newtab", "about:blank", null};
        for (String g : good) assertFalse(String.valueOf(g), Adult.isAdult(g));
    }

    @Test public void search_queries_are_ignored_only_hosts_matter() {
        assertFalse(Adult.isAdult("pornhub login"));
        assertFalse(Adult.isAdult("xvideos"));          // typed without a dot: not a host yet
        assertTrue(Adult.isAdult("xvideos.com"));
    }

    @Test public void browser_and_url_bar_tables_are_sane() {
        assertTrue(Targets.isBrowser("com.android.chrome"));
        assertTrue(Targets.isBrowser("com.brave.browser"));
        assertFalse(Targets.isBrowser("com.whatsapp"));
        assertEquals("url_bar", Targets.URL_BAR_IDS[0]);
        assertTrue(Targets.isKillable("com.android.chrome") && Targets.isKillable("com.instagram.android"));
        assertFalse(Targets.isKillable("com.android.settings") || Targets.isKillable("com.stayclean.guard"));
    }

    @Test public void manifest_xml_filter_matches_the_code_filter() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/xml/guard_service.xml")));
        Matcher m = Pattern.compile("android:packageNames=\"([^\"]+)\"").matcher(xml);
        assertTrue(m.find());
        assertEquals(new TreeSet<>(Arrays.asList(m.group(1).split(","))), new TreeSet<>(Arrays.asList(Targets.dayFilter())));
        assertTrue(xml.contains("typeWindowStateChanged"));
    }

    // ================= night lock: Instagram + X =================
    @Test public void social_window_is_11pm_to_5am_exact() {
        assertFalse(Schedule.socialWindow(ist(22, 59, 59)));
        assertTrue(Schedule.socialWindow(ist(23, 0, 0)));
        assertTrue(Schedule.socialWindow(ist(0, 0, 0)));
        assertTrue(Schedule.socialWindow(ist(4, 59, 59)));
        assertFalse(Schedule.socialWindow(ist(5, 0, 0)));
        assertFalse(Schedule.socialWindow(ist(15, 0, 0)));
    }

    @Test public void social_rule_needs_toggle_window_and_the_right_app() {
        for (String pkg : new String[]{"com.instagram.android", "com.twitter.android"}) {
            assertTrue(Targets.socialBlocked(pkg, true, true));
            assertFalse(Targets.socialBlocked(pkg, false, true));    // toggle off
            assertFalse(Targets.socialBlocked(pkg, true, false));    // outside 11pm-5am
        }
        assertFalse(Targets.socialBlocked("com.whatsapp", true, true));
        assertFalse(Targets.socialBlocked("com.twitter.android.lite", true, true));
        assertFalse(Targets.socialBlocked(null, true, true));
    }

    @Test public void toggle_can_only_be_switched_off_between_6am_and_11pm() {
        assertTrue(Schedule.canDisableToggle(ist(6, 0, 0)));
        assertTrue(Schedule.canDisableToggle(ist(14, 0, 0)));
        assertTrue(Schedule.canDisableToggle(ist(22, 59, 59)));
        assertFalse(Schedule.canDisableToggle(ist(23, 0, 0)));
        assertFalse(Schedule.canDisableToggle(ist(2, 0, 0)));
        assertFalse(Schedule.canDisableToggle(ist(5, 59, 59)));
    }

    @Test public void social_window_survives_clock_tampering() {
        TrustedClock c = BackTest.clock(new HashMap<>());
        c.setAnchor(ist(23, 30, 0), 0, 1);
        assertTrue(c.socialWindowNow(ist(14, 0, 0), 60_000, 1));       // clock moved to afternoon: still locked
        assertTrue(c.socialWindowNow(ist(23, 31, 0), 60_000, 1));
    }

    @Test public void social_phrases_are_valid_and_cycle() {
        Phrases p = new Phrases(Phrases.SOCIAL_LINES, new Random(7));
        Set<String> seen = new HashSet<>();
        String prev = null;
        for (int i = 0; i < Phrases.SOCIAL_LINES.length * 5; i++) {
            String s = p.next();
            assertNotEquals(prev, s);
            prev = s;
            seen.add(s);
        }
        assertEquals(Phrases.SOCIAL_LINES.length, seen.size());
        for (String s : Phrases.SOCIAL_LINES) assertTrue(s, s.length() > 0 && s.length() <= 36);
        assertEquals(Phrases.SOCIAL_LINES.length, new HashSet<>(Arrays.asList(Phrases.SOCIAL_LINES)).size());
    }

    // ================= Instagram: 5 minutes per 30-minute interval =================
    static final long MIN = 60_000L, SEC = 1_000L;

    @Test public void continuous_use_is_cut_at_five_minutes_and_stays_locked_until_the_interval_ends() {
        UsageMeter m = new UsageMeter();
        long t0 = 10 * 3_600_000L, blockedAt = -1;
        for (long t = t0; t <= t0 + 29 * MIN + 59 * SEC; t += SEC) {
            UsageMeter.Verdict v = m.sample(t, true);
            if (v == UsageMeter.Verdict.BLOCK && blockedAt < 0) blockedAt = t;
            if (blockedAt >= 0) assertEquals("must stay blocked at +" + (t - t0) / SEC + "s", UsageMeter.Verdict.BLOCK, v);
        }
        assertEquals(5 * MIN, blockedAt - t0);                       // exactly five minutes of use
        assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t0 + 30 * MIN, true));   // new interval starts
        assertEquals(30 * MIN, m.start() - t0);
    }

    @Test public void new_interval_gives_a_fresh_five_minutes() {
        UsageMeter m = new UsageMeter();
        long t0 = 0;
        for (long t = t0; t < t0 + 6 * MIN; t += SEC) m.sample(t, true);
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t0 + 6 * MIN, true));
        long t1 = t0 + 31 * MIN, blockedAt = -1;
        for (long t = t1; t < t1 + 10 * MIN; t += SEC) if (m.sample(t, true) == UsageMeter.Verdict.BLOCK) { blockedAt = t; break; }
        assertEquals(5 * MIN, blockedAt - t1);
    }

    @Test public void short_visits_add_up_across_the_interval() {
        UsageMeter m = new UsageMeter();
        long t = 0;
        for (int visit = 0; visit < 3; visit++) {                    // 3 visits of 100 s with gaps in between
            for (int s = 0; s < 100; s++, t += SEC) m.sample(t, true);
            m.sample(t, false);
            t += 3 * MIN;                                            // away for 3 minutes
        }
        // used about 300 s so far; 200 s more fits... then blocked
        long start = t, blockedAt = -1;
        for (int s = 0; s < 400; s++, t += SEC) if (m.sample(t, true) == UsageMeter.Verdict.BLOCK) { blockedAt = t; break; }
        assertTrue(blockedAt > 0);
        assertTrue("blocked after about 0-5 s of the 4th visit", blockedAt - start <= 5 * SEC + 10 * SEC);
    }

    @Test public void time_away_does_not_count_and_a_screen_off_gap_is_capped() {
        UsageMeter m = new UsageMeter();
        m.sample(0, true);
        for (long t = SEC; t <= 60 * SEC; t += SEC) m.sample(t, true);          // 60 s used
        // screen turned off with app foreground: next sample is 20 minutes later
        m.sample(60 * SEC + 20 * MIN, true);
        assertTrue("gap credited at most 5 s", m.used() <= 60 * SEC + 6 * SEC);
    }

    @Test public void lockout_minutes_message_rounds_up() {
        UsageMeter m = new UsageMeter();
        long t0 = 0;
        for (long t = t0; t <= t0 + 5 * MIN + 2 * SEC; t += SEC) m.sample(t, true);
        long now = t0 + 5 * MIN + 2 * SEC;
        assertEquals(25, m.lockoutMinutes(now));                      // 24m58s left -> "25 min"
        assertEquals(1, m.lockoutMinutes(t0 + 30 * MIN - 5 * SEC));
        assertEquals(1, m.lockoutMinutes(t0 + 31 * MIN));             // never "0 min"
        assertEquals(0, m.lockoutMs(t0 + 31 * MIN));
    }

    @Test public void state_survives_a_process_restart() {
        UsageMeter a = new UsageMeter();
        for (long t = 0; t < 4 * MIN; t += SEC) a.sample(t, true);
        UsageMeter b = new UsageMeter(a.start(), a.used());           // reloaded from disk
        long blockedAt = -1;
        for (long t = 10 * MIN; t < 12 * MIN; t += SEC) if (b.sample(t, true) == UsageMeter.Verdict.BLOCK) { blockedAt = t; break; }
        assertTrue("about one minute of allowance left", blockedAt >= 0 && blockedAt - 10 * MIN <= 62 * SEC);
    }

    @Test public void clock_moved_backwards_never_resets_the_interval() {
        UsageMeter m = new UsageMeter();
        long t0 = 100 * MIN;
        for (long t = t0; t <= t0 + 6 * MIN; t += SEC) m.sample(t, true);
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t0 + 6 * MIN, true));
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t0 - 50 * MIN, true));   // clock set back an hour
    }

    // ================= regression: the bug that made the adult filter silent =================
    @Test public void accessibility_config_reports_view_ids_and_windows() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/xml/guard_service.xml")));
        // Without flagReportViewIds, findAccessibilityNodeInfosByViewId("...:id/url_bar") returns nothing at all.
        assertTrue(xml.contains("flagReportViewIds"));
        assertTrue(xml.contains("flagRetrieveInteractiveWindows"));   // popup menus live in their own window
        assertTrue(xml.contains("canRetrieveWindowContent=\"true\""));
        assertTrue(xml.contains("isAccessibilityTool=\"true\""));
    }

    @Test public void guard_sets_the_flags_in_code_too() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/stayclean/GuardService.java")));
        assertTrue(src.contains("FLAG_REPORT_VIEW_IDS"));
        assertTrue(src.contains("FLAG_RETRIEVE_INTERACTIVE_WINDOWS"));
    }

    // ================= tab closing: label matching =================
    @Test public void tab_switcher_button_labels() {
        assertTrue(TabClose.isTabSwitcherLabel("Switch or close tabs. 3 open tabs"));
        assertTrue(TabClose.isTabSwitcherLabel("switch or close tabs"));
        assertTrue(TabClose.isTabSwitcherLabel("1 open tab"));
        assertFalse(TabClose.isTabSwitcherLabel("New tab"));
        assertFalse(TabClose.isTabSwitcherLabel(null));
    }

    @Test public void close_tab_menu_item_is_exact() {
        assertTrue(TabClose.isCloseTabItem("Close tab"));
        assertTrue(TabClose.isCloseTabItem("  close TAB "));
        assertFalse(TabClose.isCloseTabItem("Close all tabs"));
        assertFalse(TabClose.isCloseTabItem("Close XHamster tab"));
        assertFalse(TabClose.isCloseTabItem(null));
    }

    @Test public void grid_close_buttons_never_match_menu_items_or_close_all() {
        assertTrue(TabClose.isCardCloseLabel("Close XHamster - Free Videos tab"));
        assertTrue(TabClose.isCardCloseLabel("Close Google tab"));
        assertFalse(TabClose.isCardCloseLabel("Close tab"));
        assertFalse(TabClose.isCardCloseLabel("Close all tabs"));
        assertFalse(TabClose.isCardCloseLabel("Close all Incognito tabs"));
        assertFalse(TabClose.isCardCloseLabel("New tab"));
    }

    @Test public void card_choice_prefers_the_matching_site_and_never_guesses_between_good_tabs() {
        List<String> three = Arrays.asList("Close Google tab", "Close XHamster - Free Porn Videos tab", "Close BBC News tab");
        assertEquals(1, TabClose.pickCard(three, "xhamster.com"));
        assertEquals(1, TabClose.pickCard(three, "www.xhamster.com"));
        assertEquals(0, TabClose.pickCard(Arrays.asList("Close Free videos tab"), "xnxx.com"));    // only one tab: certain
        assertEquals(-1, TabClose.pickCard(three, "xnxx.com"));                                       // title gives no clue: do not guess
        assertEquals(-1, TabClose.pickCard(Collections.<String>emptyList(), "xnxx.com"));
        assertEquals(1, TabClose.pickCard(Arrays.asList("Close all tabs", "Close Home tab"), "x.xxx"));
        assertEquals(Arrays.asList("xhamster"), TabClose.hostHints("www.xhamster.com"));
        assertTrue(TabClose.hostHints("ok.xxx").isEmpty());
    }

    @Test public void every_site_the_tester_named_is_matched() {
        for (String s : new String[]{"xhamster.com", "www.xhamster.com", "xnxx.com", "www.xnxx.com", "ok.xxx", "https://ok.xxx/", "xvideos.com", "pornhub.com"})
            assertTrue(s, Adult.isAdult(s));
    }
}
