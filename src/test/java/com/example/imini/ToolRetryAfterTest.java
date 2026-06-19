package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRetryAfterTest {

    @Test
    void retryAfterMsClampsToWindow() {
        assertEquals(60_000L, ToolRateLimiter.retryAfterMs(1000, 60_000, 1000));     // just opened
        assertEquals(0L, ToolRateLimiter.retryAfterMs(1000, 60_000, 61_000));        // window elapsed
        assertEquals(0L, ToolRateLimiter.retryAfterMs(1000, 60_000, 70_000));        // past window
        assertEquals(40_000L, ToolRateLimiter.retryAfterMs(1000, 60_000, 21_000));   // 40s remain
        assertEquals(60_000L, ToolRateLimiter.retryAfterMs(5000, 60_000, 1000));     // clock skew (negative)
    }

    @Test
    void retryAfterSecondsZeroWhenUnconfiguredOrAllowed() throws Exception {
        ToolRateLimiter rl = new ToolRateLimiter(null);
        set(rl, "enabled", true);
        set(rl, "limits", Map.of("web_fetch", new long[]{2L, 60_000L}));
        // no calls yet -> no state -> 0
        assertEquals(0L, rl.retryAfterSeconds("alice", "web_fetch", 1_000_000L));
        // unconfigured tool -> 0
        assertEquals(0L, rl.retryAfterSeconds("alice", "read_file", 1_000_000L));
    }

    @Test
    void retryAfterSecondsPositiveAfterUse() throws Exception {
        ToolRateLimiter rl = new ToolRateLimiter(null);
        set(rl, "enabled", true);
        set(rl, "limits", Map.of("web_fetch", new long[]{2L, 60_000L}));
        long t = 1_000_000L;
        rl.allow("alice", "web_fetch", t); // opens the window
        long secs = rl.retryAfterSeconds("alice", "web_fetch", t + 5_000);
        assertTrue(secs > 0 && secs <= 60, "expected 1..60, got " + secs);
    }

    private static void set(Object o, String f, Object v) throws Exception {
        var fl = ToolRateLimiter.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }
}
