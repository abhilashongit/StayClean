package com.stayclean;

import static org.junit.Assert.*;

import java.time.*;
import java.util.*;
import org.junit.Test;

public class BackTest {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static long ist(int y, int mo, int d, int h, int mi, int s, int ms) {
        return ZonedDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000, IST).toInstant().toEpochMilli();
    }
    static long ist(int h, int mi, int s) { return ist(2026, 11, 20, h, mi, s, 0); }

    static TrustedClock clock(Map<String, Long> m) {
        return new TrustedClock(new TrustedClock.Store() {
            public long get(String k, long d) { return m.getOrDefault(k, d); }
            public void put(String k, long v) { m.put(k, v); }
        });
    }

    // ---------- schedule ----------
    @Test public void boundaries_exact() {
        assertFalse(Schedule.inWindow(ist(2026, 11, 19, 23, 59, 59, 999)));
        assertTrue(Schedule.inWindow(ist(2026, 11, 20, 0, 0, 0, 0)));
        assertTrue(Schedule.inWindow(ist(2026, 11, 20, 0, 0, 0, 1)));
        assertTrue(Schedule.inWindow(ist(2026, 11, 20, 4, 59, 59, 999)));
        assertFalse(Schedule.inWindow(ist(2026, 11, 20, 5, 0, 0, 0)));
        assertFalse(Schedule.inWindow(ist(2026, 11, 20, 12, 0, 0, 0)));
    }

    @Test public void every_minute_for_two_years_matches_reference() {
        long t = ist(2026, 1, 1, 0, 0, 0, 0);
        for (int i = 0; i < 2 * 365 * 24 * 60; i++, t += 60_000L) {
            int h = Instant.ofEpochMilli(t).atZone(IST).getHour();
            assertEquals("minute " + i, h < 5, Schedule.inWindow(t));
            assertEquals(h >= 23 || h < 6, Schedule.tamperLock(t));
        }
    }

    @Test public void phone_timezone_cannot_move_the_window() {
        TimeZone orig = TimeZone.getDefault();
        try {
            long inside = ist(2, 30, 0), outside = ist(14, 0, 0);
            for (String z : new String[]{"UTC", "America/New_York", "Pacific/Kiritimati", "Asia/Tokyo", "Europe/Ljubljana"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(z));
                assertTrue(z, Schedule.inWindow(inside));
                assertFalse(z, Schedule.inWindow(outside));
            }
        } finally { TimeZone.setDefault(orig); }
    }

    @Test public void msUntilWindow() {
        assertEquals(0, Schedule.msUntilWindow(ist(1, 0, 0)));
        assertEquals(60_000, Schedule.msUntilWindow(ist(2026, 11, 19, 23, 59, 0, 0)));
        assertEquals(19L * 3_600_000, Schedule.msUntilWindow(ist(5, 0, 0)));
    }

    // ---------- force-open ----------
    @Test public void force_open_each_target_at_night_is_blocked() {
        for (String pkg : Targets.BLOCKED_APPS)
            for (long t : new long[]{ist(0, 0, 0), ist(0, 0, 1), ist(2, 51, 0), ist(4, 59, 59)})
                assertEquals(Targets.Decision.BLOCK, Targets.decide(pkg, Schedule.inWindow(t), Schedule.tamperLock(t), false));
    }

    @Test public void open_outside_window_is_allowed() {
        for (String pkg : Targets.BLOCKED_APPS)
            for (long t : new long[]{ist(5, 0, 0), ist(12, 0, 0), ist(23, 59, 59)})
                assertEquals(Targets.Decision.IGNORE, Targets.decide(pkg, Schedule.inWindow(t), Schedule.tamperLock(t), false));
    }

    @Test public void other_apps_and_lookalikes_untouched_at_night() {
        long t = ist(2, 0, 0);
        for (String pkg : new String[]{"com.whatsapp", "com.google.android.apps.maps", "in.swiggy.android.fake",
                "com.application.zomato2", "in.swiggy", "", null})
            assertEquals(String.valueOf(pkg), Targets.Decision.IGNORE, Targets.decide(pkg, true, true, false));
    }

    @Test public void repeated_force_open_spam_produces_one_overlay_at_a_time() {
        Gate g = new Gate();
        int cycles = 0;
        for (int i = 0; i < 500; i++) if (g.tryAcquire()) cycles++;   // 500 events in a burst
        assertEquals(1, cycles);
        g.release();                                                   // overlay finished, user reopens
        assertTrue(g.tryAcquire());
        assertFalse(g.tryAcquire());
    }

    // ---------- tamper ----------
    @Test public void settings_pages_for_this_app_are_shut_11pm_to_6am_only() {
        String[] hosts = {"com.android.settings", "com.google.android.packageinstaller",
                "com.google.android.permissioncontroller", "com.miui.securitycenter", "com.samsung.android.settings.x"};
        for (String h : hosts) {
            assertTrue(h, Targets.isTamperHost(h));
            assertEquals(Targets.Decision.BLOCK, Targets.decide(h, false, Schedule.tamperLock(ist(23, 30, 0)), true));
            assertEquals(Targets.Decision.BLOCK, Targets.decide(h, true, Schedule.tamperLock(ist(5, 59, 59)), true));
            assertEquals(Targets.Decision.IGNORE, Targets.decide(h, false, Schedule.tamperLock(ist(6, 0, 0)), true));
            assertEquals(Targets.Decision.IGNORE, Targets.decide(h, false, Schedule.tamperLock(ist(15, 0, 0)), true));
            assertEquals(Targets.Decision.IGNORE, Targets.decide(h, true, true, false));   // unrelated settings page
        }
        assertEquals(Targets.Decision.IGNORE, Targets.decide("com.whatsapp", true, true, true)); // text in a chat
    }

    @Test public void tamper_text_matching() {
        assertTrue(Targets.matchesTamperText("Stay Clean"));
        assertTrue(Targets.matchesTamperText("STAY CLEAN, Accessibility service"));
        assertTrue(Targets.matchesTamperText("Set time automatically"));
        assertTrue(Targets.matchesTamperText("Automatic date and time"));
        assertFalse(Targets.matchesTamperText("Date & time"));          // plain menu entry is fine
        assertFalse(Targets.matchesTamperText("Battery"));
        assertFalse(Targets.matchesTamperText(null));
    }

    // ---------- clock tampering ----------
    @Test public void moving_the_phone_clock_to_daytime_does_not_unlock() {
        Map<String, Long> m = new HashMap<>();
        TrustedClock c = clock(m);
        long realAnchor = ist(1, 0, 0), elapsed = 1_000_000;
        c.setAnchor(realAnchor, elapsed, 5);
        // 30 minutes later (real), user sets system clock to 2 pm
        long elapsedNow = elapsed + 30 * 60_000L;
        assertTrue(c.blockedNow(ist(14, 0, 0), elapsedNow, 5));
        assertTrue(c.tamperLockNow(ist(14, 0, 0), elapsedNow, 5));
    }

    @Test public void trusted_time_follows_elapsed_clock_through_sleep() {
        Map<String, Long> m = new HashMap<>();
        TrustedClock c = clock(m);
        c.setAnchor(ist(2026, 11, 20, 23, 0, 0, 0), 0, 1);
        long after2h = c.trustedNow(2 * 3_600_000L, 1);
        assertEquals(ist(2026, 11, 21, 1, 0, 0, 0), after2h);
        assertTrue(Schedule.inWindow(after2h));
    }

    @Test public void clock_set_into_night_blocks_early_never_late() {
        TrustedClock c = clock(new HashMap<>());
        c.setAnchor(ist(15, 0, 0), 0, 1);
        assertTrue(c.blockedNow(ist(1, 0, 0), 10_000, 1));       // system says night -> block (stricter)
        assertFalse(c.blockedNow(ist(15, 0, 10), 10_000, 1));    // both say day -> open
    }

    @Test public void reboot_invalidates_anchor_and_falls_back_to_system_clock() {
        TrustedClock c = clock(new HashMap<>());
        c.setAnchor(ist(1, 0, 0), 5_000_000, 7);
        assertEquals(-1, c.trustedNow(10_000, 8));               // boot id changed
        assertTrue(c.blockedNow(ist(2, 0, 0), 10_000, 8));       // system clock still blocks at night
        assertEquals(-1, c.trustedNow(1_000, 7));                // elapsed went backwards in same boot id: distrust
    }

    @Test public void no_anchor_yet_uses_system_clock() {
        TrustedClock c = clock(new HashMap<>());
        assertTrue(c.blockedNow(ist(3, 0, 0), 0, 1));
        assertFalse(c.blockedNow(ist(9, 0, 0), 0, 1));
    }

    // ---------- force-close failover ----------
    @Test public void when_accessibility_is_killed_backup_takes_over() {
        assertEquals(Targets.Enforcer.PRIMARY_ACCESSIBILITY, Targets.chooseEnforcer(true, true, true));
        assertEquals(Targets.Enforcer.FALLBACK_OVERLAY, Targets.chooseEnforcer(false, true, true));
        assertEquals(Targets.Enforcer.FALLBACK_HOME_ONLY, Targets.chooseEnforcer(false, true, false));
        assertEquals(Targets.Enforcer.NONE, Targets.chooseEnforcer(false, false, true));
    }


    @Test public void usage_event_tracker_sees_open_switch_and_force_close() {
        FgTracker f = new FgTracker();
        f.onEvent("com.android.launcher", FgTracker.RESUMED);
        assertFalse(Targets.isBlockedApp(f.current()));
        f.onEvent("com.android.launcher", FgTracker.PAUSED);
        f.onEvent("in.swiggy.android", FgTracker.RESUMED);                  // force open
        assertTrue(Targets.isBlockedApp(f.current()));
        f.onEvent("in.swiggy.android", FgTracker.PAUSED);                   // user (or we) leave
        assertNull(f.current());
        f.onEvent("com.application.zomato", FgTracker.RESUMED);             // switch via recents
        assertTrue(Targets.isBlockedApp(f.current()));
        f.onEvent("in.swiggy.android", FgTracker.PAUSED);                   // stale pause of another app: ignored
        assertEquals("com.application.zomato", f.current());
        f.onEvent("com.application.zomato", FgTracker.PAUSED);              // process killed
        assertNull(f.current());
        f.onEvent(null, FgTracker.RESUMED);                                 // garbage tolerated
        assertNull(f.current());
    }


    // ---------- phases: sleep by day, wake at night ----------
    @Test public void phase_boundaries_exact() {
        assertEquals(Schedule.Phase.DAY, Schedule.phase(ist(22, 54, 59)));
        assertEquals(Schedule.Phase.ACTIVE, Schedule.phase(ist(22, 55, 0)));
        assertEquals(Schedule.Phase.ACTIVE, Schedule.phase(ist(23, 0, 0)));
        assertEquals(Schedule.Phase.ACTIVE, Schedule.phase(ist(0, 0, 0)));
        assertEquals(Schedule.Phase.ACTIVE, Schedule.phase(ist(5, 4, 59)));
        assertEquals(Schedule.Phase.TAMPER, Schedule.phase(ist(5, 5, 0)));
        assertEquals(Schedule.Phase.TAMPER, Schedule.phase(ist(5, 59, 59)));
        assertEquals(Schedule.Phase.DAY, Schedule.phase(ist(6, 0, 0)));
        assertEquals(Schedule.Phase.DAY, Schedule.phase(ist(14, 0, 0)));
    }

    @Test public void active_phase_covers_every_block_window_and_awake_covers_tamper_lock() {
        long t = ist(2026, 1, 1, 0, 0, 0, 0);
        for (int i = 0; i < 2 * 365 * 24 * 60; i++, t += 60_000L) {
            if (Schedule.inWindow(t) || Schedule.socialWindow(t)) assertEquals(Schedule.Phase.ACTIVE, Schedule.phase(t));
            if (Schedule.tamperLock(t)) assertNotEquals(Schedule.Phase.DAY, Schedule.phase(t));
        }
    }

    @Test public void alarm_chain_wakes_at_every_boundary_and_sleeps_by_day() {
        // Follow the real alarm chain for 5 days from a random-ish start; check each boundary gets an alarm right after it.
        long t = ist(2026, 11, 19, 3, 24, 0, 0), end = t + 5L * 86_400_000L;
        List<Long> alarms = new ArrayList<>();
        while (t < end) {
            long d = Schedule.nextAlarmDelayMs(t);
            assertTrue(d >= 1000);
            t += d;
            alarms.add(t);
        }
        int[][] boundaries = {{0, 0}, {5, 0}, {5, 5}, {6, 0}, {22, 55}};
        for (int day = 20; day <= 23; day++) {
            for (int[] b : boundaries) {
                long B = ist(2026, 11, day, b[0], b[1], 0, 0);
                boolean hit = false;
                for (long a : alarms) if (a >= B && a <= B + 2000) hit = true;
                assertTrue("no alarm just after " + b[0] + ":" + b[1] + " day " + day, hit);
            }
        }
        // By day (06:00 to 22:55) the chain contains only the boundary alarms, nothing in between.
        for (long a : alarms) {
            if (Schedule.phase(a - 3000) == Schedule.Phase.DAY && Schedule.phase(a) == Schedule.Phase.DAY) {
                long msIntoIst = Math.floorMod(a + Schedule.IST_OFFSET_MS, 86_400_000L);
                fail("stray daytime alarm at IST ms-of-day " + msIntoIst);
            }
        }
    }

    @Test public void active_phase_keepalive_is_at_most_five_minutes() {
        assertTrue(Schedule.nextAlarmDelayMs(ist(2, 0, 0)) <= 5 * 60_000L);
        assertTrue(Schedule.nextAlarmDelayMs(ist(14, 0, 0)) > 8 * 3_600_000L);   // day: sleeps until 22:55
    }

    @Test public void trusted_phase_is_never_looser_than_either_clock() {
        TrustedClock c = clock(new HashMap<>());
        assertEquals(Schedule.Phase.ACTIVE, c.phaseNow(ist(1, 0, 0), 0, 1));               // no anchor: system
        c.setAnchor(ist(1, 0, 0), 0, 1);
        assertEquals(Schedule.Phase.ACTIVE, c.phaseNow(ist(14, 0, 0), 60_000, 1));         // clock moved to daytime
        c.setAnchor(ist(14, 0, 0), 0, 2);
        assertEquals(Schedule.Phase.ACTIVE, c.phaseNow(ist(1, 0, 0), 60_000, 2));          // clock moved to night
        assertEquals(Schedule.Phase.DAY, c.phaseNow(ist(14, 1, 0), 60_000, 2));
    }

    // ---------- phrases ----------
    @Test public void phrases_cycle_through_all_before_repeating_and_never_back_to_back() {
        Phrases p = new Phrases(new Random(42));
        String prev = null;
        for (int round = 0; round < 200; round++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < Phrases.ALL.length; i++) {
                String s = p.next();
                assertTrue("repeat inside a round: " + s, seen.add(s));
                assertNotEquals(prev, s);
                prev = s;
            }
            assertEquals(Phrases.ALL.length, seen.size());
        }
    }

    @Test public void phrases_are_short_clean_and_include_the_original() {
        List<String> all = Arrays.asList(Phrases.ALL);
        assertTrue(all.contains("Have something light."));
        assertEquals(all.size(), new HashSet<>(all).size());
        for (String s : all) {
            assertTrue(s, s.length() > 0 && s.length() <= 36);
            assertFalse(s, s.contains("\n"));
        }
    }
}
