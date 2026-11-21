package com.stayclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class V18Test {
    // ---- which service a website belongs to ----
    @Test public void instagram_and_x_websites_are_recognised() {
        assertEquals(Social.Svc.IG, Social.ofUrl("instagram.com"));
        assertEquals(Social.Svc.IG, Social.ofUrl("https://www.instagram.com/reels/abc"));
        assertEquals(Social.Svc.IG, Social.ofUrl("m.instagram.com/explore"));
        assertEquals(Social.Svc.X, Social.ofUrl("x.com/home"));
        assertEquals(Social.Svc.X, Social.ofUrl("https://twitter.com/someone"));
        assertEquals(Social.Svc.X, Social.ofUrl("mobile.twitter.com"));
    }

    @Test public void lookalike_and_other_sites_are_not() {
        assertNull(Social.ofUrl("notinstagram.com"));
        assertNull(Social.ofUrl("instagram.com.evil.io"));
        assertNull(Social.ofUrl("box.com"));
        assertNull(Social.ofUrl("tx.com"));
        assertNull(Social.ofUrl("google.com/search?q=instagram.com"));
        assertNull(Social.ofUrl(null));
    }

    @Test public void apps_map_to_their_own_service() {
        assertEquals(Social.Svc.IG, Social.ofApp(Targets.INSTAGRAM));
        assertEquals(Social.Svc.X, Social.ofApp(Targets.X_APP));
        assertNull(Social.ofApp("com.android.chrome"));
    }

    @Test public void instagram_and_x_keep_separate_settings() {
        assertNotEquals(Social.Svc.IG.meterPrefs, Social.Svc.X.meterPrefs);
        assertNotEquals(Social.Svc.IG.limitKey, Social.Svc.X.limitKey);
        assertNotEquals(Social.Svc.IG.dayKey, Social.Svc.X.dayKey);
        // Instagram keeps the v1.7 keys, so an existing limit carries over on update
        assertEquals("meter", Social.Svc.IG.meterPrefs);
        assertEquals("limit_ms", Social.Svc.IG.limitKey);
        assertEquals("limit_day", Social.Svc.IG.dayKey);
    }

    // ---- read-only lock check used while closing a tab ----
    @Test public void locked_at_reflects_the_interval_without_crediting_time() {
        UsageMeter m = new UsageMeter();
        m.setAllowance(30_000);
        assertFalse(m.lockedAt(0));
        long t = 0; m.sample(t, true);
        for (int i = 0; i < 6; i++) { t += 5_000; m.sample(t, true); }
        assertTrue(m.lockedAt(t));
        assertTrue(m.lockedAt(t + 29 * 60_000L - 30_000));
        assertFalse(m.lockedAt(t + 31 * 60_000L));                 // next interval is open again
        m.setAllowance(0);
        assertTrue(m.lockedAt(t + 60 * 60_000L));                  // zero is always closed
    }

    // ---- Reddit search words ----
    @Test public void adult_searches_are_caught() {
        assertTrue(RedditGuard.isAdultQuery("porn", false));
        assertTrue(RedditGuard.isAdultQuery("best porn videos", true));
        assertTrue(RedditGuard.isAdultQuery("NSFW memes", false));
        assertTrue(RedditGuard.isAdultQuery("sexy girls", false));
        assertTrue(RedditGuard.isAdultQuery("sex ", true));          // finished word while typing
        assertTrue(RedditGuard.isAdultQuery("boobs", true));
        assertTrue(RedditGuard.isAdultQuery("gonewild", true));
    }

    @Test public void ordinary_searches_stay_open() {
        assertFalse(RedditGuard.isAdultQuery("essex football", false));
        assertFalse(RedditGuard.isAdultQuery("sussex university", false));
        assertFalse(RedditGuard.isAdultQuery("class assignment help", false));
        assertFalse(RedditGuard.isAdultQuery("cumulative gpa", false));
        assertFalse(RedditGuard.isAdultQuery("tithe church", false));
        assertFalse(RedditGuard.isAdultQuery("f1 spoilers", false));
        assertFalse(RedditGuard.isAdultQuery("", false));
        assertFalse(RedditGuard.isAdultQuery(null, false));
    }

    @Test public void short_unfinished_last_word_waits_while_typing() {
        assertFalse(RedditGuard.isAdultQuery("sex", true));          // could still become "sextant"
        assertTrue(RedditGuard.isAdultQuery("sex", false));          // search submitted
        assertFalse(RedditGuard.isAdultQuery("sextant", false));
    }

    @Test public void browser_search_and_saved_pages() {
        assertTrue(RedditGuard.isBlockedUrl("https://www.reddit.com/search/?q=porn"));
        assertTrue(RedditGuard.isBlockedUrl("reddit.com/search?q=hot+nude+pics&type=media"));
        assertTrue(RedditGuard.isBlockedUrl("old.reddit.com/r/pics/search?q=nsfw&restrict_sr=on"));
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/search/?q=essex+housing"));
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/r/pics/comments/abc/porn_title_in_slug"));  // not a search
        assertTrue(RedditGuard.isBlockedUrl("https://www.reddit.com/user/someone/saved/"));
        assertTrue(RedditGuard.isBlockedUrl("reddit.com/u/someone/saved"));
        assertTrue(RedditGuard.isBlockedUrl("old.reddit.com/user/someone/saved?sort=new"));
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/user/someone"));
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/user/someone/submitted"));
        assertFalse(RedditGuard.isBlockedUrl("google.com/user/someone/saved"));
    }

    // ---- Reddit app screen ----
    @Test public void search_box_on_screen_is_judged() {
        RedditGuard.Screen s = new RedditGuard.Screen();
        s.field("nsfw art", true);
        assertTrue(s.adultSearch());
        RedditGuard.Screen t = new RedditGuard.Screen();
        t.field("monza race", false);
        assertFalse(t.adultSearch());
        assertFalse(new RedditGuard.Screen().adultSearch());
    }

    @Test public void probe_keeps_short_top_labels_only() {
        RedditGuard.Screen s = new RedditGuard.Screen();
        s.probe("Search", 0.05f);
        s.probe("Search", 0.06f);
        s.probe("Saved", 0.1f);
        s.probe("A very long post title that is clearly not a header", 0.1f);
        s.probe("Lower", 0.6f);
        assertEquals(java.util.Arrays.asList("Search", "Saved"), s.probe);
    }
}
