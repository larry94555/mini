package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure scheduling math for local scheduled tasks. */
class ScheduleTest {

    @Test
    void isDueOnlyWhenEnabledAndReached() {
        assertTrue(Schedule.isDue(true, 1000, 1000));
        assertTrue(Schedule.isDue(true, 1000, 2000));
        assertFalse(Schedule.isDue(true, 1000, 999));
        assertFalse(Schedule.isDue(false, 1000, 2000)); // disabled
        assertFalse(Schedule.isDue(true, 0, 2000));     // not scheduled
    }

    @Test
    void firstRunClampsToMinimum() {
        long now = 10_000;
        assertEquals(now + Schedule.MIN_SECONDS * 1000, Schedule.firstRun(now, 1)); // 1s clamped up
        assertEquals(now + 60_000, Schedule.firstRun(now, 60));
    }

    @Test
    void nextRunRepeatsOrEndsOneShot() {
        long now = 10_000;
        assertEquals(0L, Schedule.nextRun(now, 30, true));               // one-shot done
        assertEquals(now + 30_000, Schedule.nextRun(now, 30, false));    // repeat
        assertEquals(now + Schedule.MIN_SECONDS * 1000, Schedule.nextRun(now, 1, false)); // clamp
    }

    @Test
    void clampSecondsFloors() {
        assertEquals(Schedule.MIN_SECONDS, Schedule.clampSeconds(1));
        assertEquals(120, Schedule.clampSeconds(120));
    }
}
