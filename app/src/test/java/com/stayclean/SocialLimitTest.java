package com.stayclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class SocialLimitTest {
    static final long MIN = 60_000L;
    static final long NONE = Long.MIN_VALUE;

    @Test public void clamp_keeps_zero_to_seven_thirty_in_half_minute_steps() {
        assertEquals(0, SocialLimit.clamp(-5 * MIN));
        assertEquals(0, SocialLimit.clamp(10_000));
        assertEquals(30_000, SocialLimit.clamp(20_000));
        assertEquals(5 * MIN, SocialLimit.clamp(5 * MIN + 9_000));
        assertEquals(450_000, SocialLimit.clamp(450_000));
        assertEquals(450_000, SocialLimit.clamp(20 * MIN));
    }

    @Test public void every_wheel_combination_is_valid_and_max_is_seven_thirty() {
        long max = 0;
        for (int m = 0; m <= 7; m++) for (int s = 0; s <= 1; s++) {
            long ms = m * MIN + s * 30_000L;
            assertEquals(ms, SocialLimit.clamp(ms));
            max = Math.max(max, ms);
        }
        assertEquals(SocialLimit.MAX_MS, max);
    }

    @Test public void one_change_a_day_by_either_clock() {
        long today = 100;
        assertEquals(SocialLimit.Verdict.OK, SocialLimit.check(5 * MIN, 3 * MIN, NONE, today, today, false));
        assertEquals(SocialLimit.Verdict.ALREADY_TODAY, SocialLimit.check(5 * MIN, 3 * MIN, today, today, today, false));
        // phone clock wound back a day: the network clock still knows it is the same day
        assertEquals(SocialLimit.Verdict.ALREADY_TODAY, SocialLimit.check(5 * MIN, 3 * MIN, today, today - 1, today, false));
        // phone clock wound forward: the stored day still matches the network clock
        assertEquals(SocialLimit.Verdict.ALREADY_TODAY, SocialLimit.check(5 * MIN, 3 * MIN, today, today + 1, today, false));
        assertEquals(SocialLimit.Verdict.OK, SocialLimit.check(5 * MIN, 3 * MIN, today - 1, today, today, false));
    }

    @Test public void raising_is_closed_at_night_lowering_is_not() {
        assertEquals(SocialLimit.Verdict.RAISE_AT_NIGHT, SocialLimit.check(5 * MIN, 7 * MIN, NONE, 1, 1, true));
        assertEquals(SocialLimit.Verdict.OK, SocialLimit.check(5 * MIN, 2 * MIN, NONE, 1, 1, true));
        assertEquals(SocialLimit.Verdict.OK, SocialLimit.check(5 * MIN, 7 * MIN, NONE, 1, 1, false));
    }

    @Test public void same_value_is_not_a_change() {
        assertEquals(SocialLimit.Verdict.SAME, SocialLimit.check(5 * MIN, 5 * MIN, 1, 1, 1, true));
    }

    @Test public void labels_read_naturally() {
        assertEquals("5 minutes", SocialLimit.label(5 * MIN));
        assertEquals("1 minute", SocialLimit.label(MIN));
        assertEquals("30 seconds", SocialLimit.label(30_000));
        assertEquals("7 min 30 sec", SocialLimit.label(450_000));
        assertEquals("0 minutes, always closed", SocialLimit.label(0));
    }

    // ---- the meter honours the allowance ----
    @Test public void meter_default_is_still_five_minutes() {
        UsageMeter m = new UsageMeter();
        long t = 0;
        assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t, true));
        for (int i = 0; i < 59; i++) { t += 5_000; assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t, true)); }
        t += 5_000;
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t, true));
    }

    @Test public void meter_blocks_at_a_shorter_allowance() {
        UsageMeter m = new UsageMeter();
        m.setAllowance(30_000);
        long t = 0;
        assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t, true));
        for (int i = 0; i < 5; i++) { t += 5_000; assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t, true)); }
        t += 5_000;
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t, true));
        assertEquals(0, m.remainingMs());
    }

    @Test public void meter_allows_the_full_seven_thirty() {
        UsageMeter m = new UsageMeter();
        m.setAllowance(SocialLimit.MAX_MS);
        long t = 0;
        m.sample(t, true);
        for (int i = 0; i < 89; i++) { t += 5_000; assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t, true)); }
        t += 5_000;
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t, true));
    }

    @Test public void zero_allowance_blocks_at_once_and_stays_blocked() {
        UsageMeter m = new UsageMeter();
        m.setAllowance(0);
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(0, true));
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(5_000, true));
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(10_000, false));
        // a fresh interval later it is still closed
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(31 * MIN, true));
    }

    @Test public void raising_mid_interval_gives_the_extra_time() {
        UsageMeter m = new UsageMeter();
        m.setAllowance(MIN);
        long t = 0;
        m.sample(t, true);
        for (int i = 0; i < 12; i++) { t += 5_000; m.sample(t, true); }
        assertEquals(UsageMeter.Verdict.BLOCK, m.sample(t + 5_000, true));
        m.setAllowance(2 * MIN);
        assertEquals(UsageMeter.Verdict.ALLOW, m.sample(t + 10_000, true));
    }
}
