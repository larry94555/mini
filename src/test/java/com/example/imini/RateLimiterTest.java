package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {
    @Test
    void stepRollsWindowAndIncrements() {
        // within the same window: count increments, start stays
        long[] a = RateLimiter.step(100, 2, 150, 1000);
        assertEquals(100, a[0]);
        assertEquals(3, a[1]);
        // window elapsed: reset to nowMs with count 1
        long[] b = RateLimiter.step(100, 9, 2000, 1000);
        assertEquals(2000, b[0]);
        assertEquals(1, b[1]);
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter rl = new RateLimiter(3, 1000L); // 3 per 1s, in-memory
        long t = 0;
        assertTrue(rl.allow("u", t));   // 1
        assertTrue(rl.allow("u", t));   // 2
        assertTrue(rl.allow("u", t));   // 3
        assertFalse(rl.allow("u", t));  // 4 -> blocked
        // new window resets
        assertTrue(rl.allow("u", t + 1000));
    }

    @Test
    void zeroLimitDisables() {
        RateLimiter rl = new RateLimiter(0);
        for (int i = 0; i < 100; i++) assertTrue(rl.allow("u", i));
    }

    @Test
    void pruneStaleRemovesElapsedWindows() {
        RateLimiter rl = new RateLimiter(5, 1000L);
        rl.allow("a", 0);
        rl.allow("b", 0);
        int removed = rl.pruneStale(2000); // both windows elapsed
        assertTrue(removed >= 2);
    }
}
