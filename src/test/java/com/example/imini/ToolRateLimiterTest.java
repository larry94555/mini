package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRateLimiterTest {

    @Test
    void parsesLimits() {
        Map<String, long[]> m = ToolRateLimiter.parseLimits("web_fetch=10/60, run_command=5/30");
        assertEquals(10L, m.get("web_fetch")[0]);
        assertEquals(60_000L, m.get("web_fetch")[1]);
        assertEquals(5L, m.get("run_command")[0]);
        assertEquals(30_000L, m.get("run_command")[1]);
    }

    @Test
    void skipsMalformedEntries() {
        Map<String, long[]> m = ToolRateLimiter.parseLimits("good=3/60, bad, worse=, =5/60, x=abc/60, y=5/0");
        assertEquals(1, m.size());
        assertTrue(m.containsKey("good"));
        assertNull(m.get("y")); // zero window rejected
    }

    @Test
    void emptyConfigYieldsNoLimits() {
        assertTrue(ToolRateLimiter.parseLimits("").isEmpty());
        assertTrue(ToolRateLimiter.parseLimits(null).isEmpty());
    }

    @Test
    void allowsUpToLimitThenThrottles() throws Exception {
        ToolRateLimiter rl = new ToolRateLimiter(null);
        set(rl, "enabled", true);
        set(rl, "limits", Map.of("web_fetch", new long[]{3L, 60_000L}));
        long t = 1_000_000L;
        // 3 allowed within the window
        assertTrue(rl.allow("alice", "web_fetch", t));
        assertTrue(rl.allow("alice", "web_fetch", t + 1));
        assertTrue(rl.allow("alice", "web_fetch", t + 2));
        // 4th throttled
        assertFalse(rl.allow("alice", "web_fetch", t + 3));
    }

    @Test
    void perTenantIsolationAndUnconfiguredToolsUnlimited() throws Exception {
        ToolRateLimiter rl = new ToolRateLimiter(null);
        set(rl, "enabled", true);
        set(rl, "limits", Map.of("web_fetch", new long[]{1L, 60_000L}));
        long t = 2_000_000L;
        assertTrue(rl.allow("alice", "web_fetch", t));
        assertFalse(rl.allow("alice", "web_fetch", t + 1));   // alice throttled
        assertTrue(rl.allow("bob", "web_fetch", t + 2));      // bob has own budget
        // a tool with no configured limit is always allowed
        assertTrue(rl.allow("alice", "read_file", t + 3));
        assertTrue(rl.allow("alice", "read_file", t + 4));
    }

    @Test
    void disabledAllowsEverything() throws Exception {
        ToolRateLimiter rl = new ToolRateLimiter(null);
        set(rl, "enabled", false);
        set(rl, "limits", Map.of("web_fetch", new long[]{1L, 60_000L}));
        long t = 3_000_000L;
        assertTrue(rl.allow("alice", "web_fetch", t));
        assertTrue(rl.allow("alice", "web_fetch", t + 1)); // not enforced
    }

    private static void set(Object o, String f, Object v) throws Exception {
        var fl = ToolRateLimiter.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }
}
