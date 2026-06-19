package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTest {

    @Test
    void slidingStepWeightsPreviousWindow() {
        // Same window, no prior history: weighted == current count.
        long[] a = RateLimiter.slidingStep(0, 0, 0, 100, 1000);
        assertEquals(0, a[0]);   // windowStart unchanged
        assertEquals(1, a[1]);   // current incremented
        assertEquals(0, a[2]);   // prev unchanged
        assertEquals(1, a[3]);   // weighted = 1

        // Crossing one boundary: previous current (10) rolls into prev; at the very start of the new
        // window the whole previous window is still in view, so weighted ~= newCurrent + prev.
        long[] b = RateLimiter.slidingStep(0, 10, 0, 1000, 1000);
        assertEquals(1000, b[0]);  // advanced by one window
        assertEquals(1, b[1]);     // current reset then +1
        assertEquals(10, b[2]);    // prev = old current
        assertEquals(11, b[3]);    // weighted = 1 + 10*(full weight)

        // Halfway into the new window, only half of prev is in view.
        long[] c = RateLimiter.slidingStep(1000, 1, 10, 1500, 1000);
        assertEquals(2, c[1]);              // current incremented
        assertEquals(10, c[2]);             // prev unchanged
        assertEquals(2 + 5, c[3]);          // weighted = 2 + 10*0.5
    }

    @Test
    void slidingClearsHistoryAfterTwoWindows() {
        long[] r = RateLimiter.slidingStep(0, 9, 9, 5000, 1000); // idle 5 windows
        assertEquals(5000, r[0]);
        assertEquals(1, r[1]);
        assertEquals(0, r[2]);
        assertEquals(1, r[3]);
    }

    @Test
    void slidingBlocksBoundaryBurstThatFixedWouldAllow() {
        // Fixed window WOULD allow 'limit' at the end of one window and 'limit' again right after the
        // boundary (a 2x burst). Sliding should block at the boundary because the prior window is in view.
        int limit = 5;
        RateLimiter sliding = new RateLimiter(limit, 1000L, null, RateLimiter.Algorithm.SLIDING);
        // Fill the window [0,1000) completely.
        for (int i = 0; i < limit; i++) assertTrue(sliding.allow("u", 0));
        // Exactly at the boundary the previous window is fully in view, so weighted = 1 + 5 = 6 > 5.
        assertFalse(sliding.allow("u", 1000), "sliding should block the boundary burst");
    }

    @Test
    void fixedAllowsBoundaryBurst() {
        int limit = 5;
        RateLimiter fixed = new RateLimiter(limit, 1000L, null, RateLimiter.Algorithm.FIXED);
        // First window starts at t=0 (first call), so it spans [0,1000).
        for (int i = 0; i < limit; i++) assertTrue(fixed.allow("u", 0));
        assertFalse(fixed.allow("u", 500));                 // blocked within the same window
        for (int i = 0; i < limit; i++) assertTrue(fixed.allow("u", 1000)); // new window: full quota again
    }

    @Test
    void slidingPruneStaleRemovesOldKeys() {
        RateLimiter sliding = new RateLimiter(5, 1000L, null, RateLimiter.Algorithm.SLIDING);
        sliding.allow("a", 0);
        sliding.allow("b", 0);
        // sliding entries are stale only after TWO windows
        assertEquals(0, sliding.pruneStale(1500)); // not yet stale
        int removed = sliding.pruneStale(3000);
        assertTrue(removed >= 2, "expected stale sliding keys removed, got " + removed);
    }

    @Test
    void algorithmAccessorReflectsChoice() {
        assertEquals(RateLimiter.Algorithm.SLIDING,
                new RateLimiter(1, 1000L, null, RateLimiter.Algorithm.SLIDING).algorithm());
        assertEquals(RateLimiter.Algorithm.FIXED, new RateLimiter(1).algorithm());
    }
}
