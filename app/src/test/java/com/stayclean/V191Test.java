package com.stayclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class V191Test {
    static long ist(int d, int h, int mi, int s) { return BackTest.ist(2026, 11, d, h, mi, s, 0); }

    // ---- emergency: one pool per night, 11 pm to 5 am ----
    @Test public void night_runs_from_11pm_to_5am_as_one_night() {
        long n = Emergency.nightOf(ist(26, 23, 0, 0));
        assertEquals(n, Emergency.nightOf(ist(27, 0, 30, 0)));
        assertEquals(n, Emergency.nightOf(ist(27, 4, 59, 0)));
        assertNotEquals(n, Emergency.nightOf(ist(27, 23, 0, 0)));      // next night is a fresh pool
    }

    @Test public void pool_is_five_minutes_and_counts_only_while_open() {
        Emergency.Pool p = new Emergency.Pool();
        p.rollTo(1); p.on = true;
        long t = 0;
        p.sample(t, true);
        for (int i = 0; i < 60; i++) { t += 1_000; p.sample(t, true); }       // one minute open
        assertEquals(4 * 60_000L, p.remaining());
        p.sample(t + 1_000, false);                                           // closed: stops counting
        long after = p.remaining();
        p.sample(t + 600_000, false);                                         // ten minutes away
        assertEquals(after, p.remaining());
    }

    @Test public void pool_runs_out_and_stays_out() {
        Emergency.Pool p = new Emergency.Pool();
        p.rollTo(1); p.on = true;
        long t = 0; p.sample(t, true);
        for (int i = 0; i < 400; i++) { t += 1_000; p.sample(t, true); }
        assertEquals(0, p.remaining());
        assertEquals(Emergency.POOL_MS, p.used);
    }

    @Test public void a_long_gap_never_overcounts() {
        Emergency.Pool p = new Emergency.Pool();
        p.rollTo(1); p.on = true;
        p.sample(0, true);
        p.sample(10 * 60_000L, true);                                         // e.g. the phone slept
        assertTrue(p.used <= 6_000L);
    }

    @Test public void not_started_means_nothing_counts() {
        Emergency.Pool p = new Emergency.Pool();
        p.rollTo(1);
        p.sample(0, true); p.sample(5_000, true);
        assertEquals(Emergency.POOL_MS, p.remaining());
    }

    @Test public void new_night_resets_pool_and_switch() {
        Emergency.Pool p = new Emergency.Pool();
        p.rollTo(1); p.on = true; p.used = 200_000;
        p.rollTo(2);
        assertFalse(p.on);
        assertEquals(Emergency.POOL_MS, p.remaining());
    }

    @Test public void clock_reads_naturally() {
        assertEquals("5:00", Emergency.clock(300_000));
        assertEquals("0:09", Emergency.clock(8_200));
        assertEquals("4:30", Emergency.clock(270_000));
    }

    // ---- wind-down: 10:50 and 10:55 pm IST ----
    @Test public void reminders_fire_at_1050_then_1055() {
        long[] a = Notes.nextWindDown(ist(26, 18, 0, 0));
        assertEquals(ist(26, 22, 50, 0), a[0]); assertEquals(10, a[1]);
        long[] b = Notes.nextWindDown(ist(26, 22, 50, 0));
        assertEquals(ist(26, 22, 55, 0), b[0]); assertEquals(5, b[1]);
        long[] c = Notes.nextWindDown(ist(26, 22, 56, 0));
        assertEquals(ist(27, 22, 50, 0), c[0]); assertEquals(10, c[1]);
    }

    @Test public void after_midnight_the_next_is_tonight() {
        long[] a = Notes.nextWindDown(ist(27, 1, 0, 0));
        assertEquals(ist(27, 22, 50, 0), a[0]);
    }

    @Test public void resume_lines_rotate() {
        String a = ResumeCard.line(), b = ResumeCard.line();
        assertNotEquals(a, b);
    }
}
