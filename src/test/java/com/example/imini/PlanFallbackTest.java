package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure decision logic for automatic plan-mode fallback on over-budget turns. */
class PlanFallbackTest {

    @Test
    void fallsBackOnlyWhenOverCapAndEnabledAndNotAlreadyPlanning() {
        assertTrue(PlanFallback.shouldFallback(9000, 7168, true, false));   // over cap
        assertFalse(PlanFallback.shouldFallback(5000, 7168, true, false));  // under cap
        assertFalse(PlanFallback.shouldFallback(9000, 7168, false, false)); // disabled
        assertFalse(PlanFallback.shouldFallback(9000, 7168, true, true));   // already planning
    }

    @Test
    void doesNotFallBackWhenCapUnknownOrExactlyAtCap() {
        assertFalse(PlanFallback.shouldFallback(9000, 0, true, false));     // unknown cap
        assertFalse(PlanFallback.shouldFallback(9000, -1, true, false));    // unknown cap
        assertFalse(PlanFallback.shouldFallback(7168, 7168, true, false));  // exactly at cap, not over
    }
}
