package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for auth parsing, key extraction, constant-time compare, and rate limiting. */
class AuthTest {

    @Test
    void parseKeysSupportsLabelsAndBareKeys() {
        Map<String, String> m = AuthFilter.parseKeys("alice:KEY123, KEYBARE456");
        assertEquals("alice", m.get("KEY123"));
        assertTrue(m.containsKey("KEYBARE456"));      // bare key keeps a truncated label
        assertEquals(2, m.size());
    }

    @Test
    void extractKeyFromHeaderOrBearer() {
        assertEquals("abc", AuthFilter.extractKey("abc", null));
        assertEquals("xyz", AuthFilter.extractKey(null, "Bearer xyz"));
        assertEquals("xyz", AuthFilter.extractKey(null, "bearer xyz")); // case-insensitive scheme
        assertNull(AuthFilter.extractKey(null, null));
        assertNull(AuthFilter.extractKey(null, "Basic foo"));
    }

    @Test
    void constantTimeEqualsBehavesLikeEquals() {
        assertTrue(AuthFilter.constantTimeEquals("secret", "secret"));
        assertFalse(AuthFilter.constantTimeEquals("secret", "secreT"));
        assertFalse(AuthFilter.constantTimeEquals("secret", null));
    }

    @Test
    void rateLimiterAllowsUpToLimitThenBlocksThenResets() {
        RateLimiter rl = new RateLimiter(2, 1000); // 2 per 1s window
        assertTrue(rl.allow("k", 0));
        assertTrue(rl.allow("k", 100));
        assertFalse(rl.allow("k", 200));            // third in window -> blocked
        assertTrue(rl.allow("k", 1100));            // new window -> allowed again
    }

    @Test
    void rateLimiterDisabledWhenZero() {
        RateLimiter rl = new RateLimiter(0);
        for (int i = 0; i < 100; i++) assertTrue(rl.allow("k", i));
    }
}
