package com.stayclean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.FileInputStream;
import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;

public class RedditGuardTest {

    @BeforeClass public static void loadList() throws Exception {
        RedditGuard.load(new FileInputStream("src/main/res/raw/reddit18.bin"));
    }

    @Test public void packed_list_decodes_fully() {
        assertTrue(RedditGuard.listOk);
        assertEquals(28228, RedditGuard.listSize());
        // Fingerprint of the largest listed community (kept as a number so no name sits in the code).
        assertTrue(listed(0x83cc5b26));
        assertFalse(listed(RedditGuard.fingerprint("askreddit")));
    }

    private static boolean listed(int fp) {
        // same lookup the app uses, but from a fingerprint
        try {
            java.lang.reflect.Field f = RedditGuard.class.getDeclaredField("list");
            f.setAccessible(true);
            int[] l = (int[]) f.get(null);
            return Arrays.binarySearch(l, fp ^ 0x80000000) >= 0;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void ordinary_and_recovery_communities_stay_open() {
        for (String n : new String[]{"askreddit", "pics", "worldnews", "india", "gaming", "spoilers", "askmen", "sex",
                "earthporn", "foodporn", "mapporn", "pornfree", "nofap", "lgbt", "askwomen", "anime_titties",
                "xxxtentacion", "nakedandafraid", "classicalartnudes", "churchoffutaba", "sfw_rule34", "bigdickproblems"}) {
            assertFalse(n, RedditGuard.isAdultCommunity(n));
            assertFalse(n, RedditGuard.isAdultCommunity("r/" + n));
        }
    }

    @Test public void name_rules_catch_unlisted_adult_names() {
        assertTrue(RedditGuard.isAdultCommunity("brandnewnsfwplace"));
        assertTrue(RedditGuard.isAdultCommunity("r/SomeHentaiArchive"));
        assertTrue(RedditGuard.isAdultCommunity("tiny_onlyfans_leaks"));
        assertFalse(RedditGuard.isAdultCommunity("cats_sfw"));            // explicit SFW naming wins
        assertFalse(RedditGuard.isAdultCommunity("u_somecreator_nsfw")); // profiles are out of scope
    }

    @Test public void names_are_normalised_and_validated() {
        assertEquals("askwomen", RedditGuard.normalize(" r/AskWomen "));
        assertEquals("askwomen", RedditGuard.normalize("/r/askwomen"));
        assertNull(RedditGuard.normalize("a"));
        assertNull(RedditGuard.normalize("has space"));
        assertNull(RedditGuard.normalize("waytoolongforacommunityname"));
        assertNull(RedditGuard.normalize("u_someone"));
    }

    @Test public void browser_addresses() {
        assertEquals(Arrays.asList("askwomen"), RedditGuard.communitiesInUrl("reddit.com/r/AskWomen/"));
        assertEquals(Arrays.asList("pics"), RedditGuard.communitiesInUrl("https://www.reddit.com/r/pics/comments/abc/title/?x=1"));
        assertEquals(Arrays.asList("a1", "b2"), RedditGuard.communitiesInUrl("old.reddit.com/r/a1+b2/new"));
        assertEquals(Arrays.asList("nsfwthing"), RedditGuard.communitiesInUrl("old.reddit.com/over18?dest=https%3A%2F%2Fold.reddit.com%2Fr%2Fnsfwthing%2F"));
        assertTrue(RedditGuard.communitiesInUrl("reddit.com").isEmpty());
        assertTrue(RedditGuard.communitiesInUrl("reddit.com/user/someone").isEmpty());
        assertTrue(RedditGuard.communitiesInUrl("reddit.com/u/someone").isEmpty());
        assertTrue(RedditGuard.communitiesInUrl("reddit.com/search/?q=r/nsfwthing").isEmpty());
        assertTrue(RedditGuard.communitiesInUrl("notreddit.com/r/nsfwthing").isEmpty());
        assertTrue(RedditGuard.communitiesInUrl("example.com/reddit.com/r/nsfwthing").isEmpty());

        assertTrue(RedditGuard.isBlockedUrl("https://www.reddit.com/r/nsfwthing/"));
        assertTrue(RedditGuard.isBlockedUrl("reddit.com/r/nsfwthing/comments/abc/a_post/"));   // a post inside one: blocked
        assertTrue(RedditGuard.isBlockedUrl("reddit.com/r/pics+nsfwthing"));                   // any part of a multi
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/r/pics/comments/abc/spoiler_post/"));  // a tagged post elsewhere: open
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/r/askwomen"));
        assertFalse(RedditGuard.isBlockedUrl("reddit.com/user/somecreator_nsfw"));
    }

    @Test public void app_screen_recognises_only_a_community_page() {
        // A community page: one name in the header plus a Join button.
        RedditGuard.Screen page = new RedditGuard.Screen();
        page.add("r/nsfwthing", 0.12f); page.add("Join", 0.15f); page.add("12K members", 0.18f);
        assertEquals("nsfwthing", page.openCommunity());

        // Reddit's own 18+ gate counts as a cue.
        RedditGuard.Screen gate = new RedditGuard.Screen();
        gate.add("r/nsfwthing", 0.10f); gate.add("You must be 18+ to view this community", 0.40f);
        assertEquals("nsfwthing", gate.openCommunity());

        // Home feed: several communities on screen -> left alone.
        RedditGuard.Screen feed = new RedditGuard.Screen();
        feed.add("r/pics", 0.15f); feed.add("Join", 0.15f); feed.add("r/nsfwthing", 0.45f);
        assertNull(feed.openCommunity());

        // A name low on the screen, or no page cue -> left alone.
        RedditGuard.Screen low = new RedditGuard.Screen();
        low.add("r/nsfwthing", 0.55f); low.add("Join", 0.56f);
        assertNull(low.openCommunity());
        RedditGuard.Screen nocue = new RedditGuard.Screen();
        nocue.add("r/nsfwthing", 0.1f);
        assertNull(nocue.openCommunity());

        // A name buried in a long comment is not a header.
        RedditGuard.Screen comment = new RedditGuard.Screen();
        comment.add("r/pics", 0.1f); comment.add("Joined", 0.1f);
        comment.add("I found this through r/nsfwthing a while ago and honestly it was not worth it at all", 0.3f);
        assertEquals("pics", comment.openCommunity());

        // Profiles never count.
        RedditGuard.Screen profile = new RedditGuard.Screen();
        profile.add("r/u_someone", 0.1f); profile.add("Join", 0.1f);
        assertNull(profile.openCommunity());
    }

    @Test public void a_bad_list_file_falls_back_to_rules() throws Exception {
        java.lang.reflect.Field f = RedditGuard.class.getDeclaredField("list");
        f.setAccessible(true);
        Object saved = f.get(null);
        boolean savedOk = RedditGuard.listOk;
        try {
            f.set(null, null);
            RedditGuard.load(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
            assertFalse(RedditGuard.listOk);
            assertEquals(0, RedditGuard.listSize());
            assertTrue(RedditGuard.isAdultCommunity("brandnewnsfwplace"));   // rules still on
            assertFalse(RedditGuard.isAdultCommunity("pics"));
        } finally {
            f.set(null, saved);
            RedditGuard.listOk = savedOk;
        }
    }
}
