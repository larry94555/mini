package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionExpiryTest {
    @Test
    void expiredWhenIdleBeyondTtl() {
        long now = 1_000_000_000L;
        long ttl = 86_400_000L; // 1 day
        assertTrue(SessionStore.isExpired(now - ttl - 1, now, ttl));
        assertFalse(SessionStore.isExpired(now - ttl + 1, now, ttl));
        assertFalse(SessionStore.isExpired(now, now, ttl));
    }

    @Test
    void ttlZeroDisablesExpiry() {
        long now = 1_000_000_000L;
        assertFalse(SessionStore.isExpired(0, now, 0));      // very old, but ttl disabled
        assertFalse(SessionStore.isExpired(0, now, -1));
    }
}
