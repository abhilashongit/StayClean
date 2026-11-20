package com.stayclean;

import static org.junit.Assert.*;

import org.junit.Test;

public class MorningTest {
    static long ist(int d, int h, int mi) { return BackTest.ist(2026, 11, d, h, mi, 0, 0); }

    @Test public void not_before_six() {
        assertFalse(Morning.due(ist(20, 5, 59), Long.MIN_VALUE));
        assertFalse(Morning.due(ist(20, 2, 0), Long.MIN_VALUE));
    }

    @Test public void greets_from_six_until_noon() {
        assertTrue(Morning.due(ist(20, 6, 0), Long.MIN_VALUE));
        assertTrue(Morning.due(ist(20, 7, 15), Long.MIN_VALUE));
        assertTrue(Morning.due(ist(20, 11, 59), Long.MIN_VALUE));
        assertFalse(Morning.due(ist(20, 12, 0), Long.MIN_VALUE));
        assertFalse(Morning.due(ist(20, 15, 0), Long.MIN_VALUE));
    }

    @Test public void once_per_ist_day() {
        long first = ist(20, 7, 15);
        long day = Morning.dayOf(first);
        assertFalse(Morning.due(ist(20, 8, 30), day));
        assertFalse(Morning.due(ist(20, 11, 0), day));
        assertTrue(Morning.due(ist(21, 6, 30), day));        // next morning greets again
    }

    @Test public void day_rolls_at_ist_midnight_not_utc() {
        assertEquals(Morning.dayOf(ist(20, 0, 0)), Morning.dayOf(ist(20, 23, 59)));
        assertNotEquals(Morning.dayOf(ist(20, 23, 59)), Morning.dayOf(ist(21, 0, 0)));
        assertEquals(7 * 60 + 15, Morning.minuteOfDay(ist(20, 7, 15)));
    }

    @Test public void unlock_at_night_or_a_wrong_clock_never_greets_early() {
        assertFalse(Morning.due(ist(20, 0, 30), Long.MIN_VALUE));
        assertFalse(Morning.due(ist(20, 23, 0), Long.MIN_VALUE));
    }
}
