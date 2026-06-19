package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfGuardTest {

    private static final byte[] KEY = "shared-secret-xyz".getBytes(StandardCharsets.UTF_8);

    @Test
    void constantTimeEqualsMatchesAndRejects() {
        assertTrue(CsrfGuard.constantTimeEquals("abc123", "abc123"));
        assertFalse(CsrfGuard.constantTimeEquals("abc123", "abc124"));
        assertFalse(CsrfGuard.constantTimeEquals("abc", "abcd"));   // length differs
        assertFalse(CsrfGuard.constantTimeEquals(null, "x"));
        assertFalse(CsrfGuard.constantTimeEquals("x", null));
    }

    @Test
    void mintedTokenVerifiesWithSameKey() {
        long now = 1_000_000L;
        String tok = CsrfGuard.mint(now, KEY, 3600);
        assertNotNull(tok);
        assertTrue(tok.contains("."));
        assertTrue(CsrfGuard.verify(tok, KEY, now + 1000));      // within TTL
    }

    @Test
    void expiredTokenRejected() {
        long now = 1_000_000L;
        String tok = CsrfGuard.mint(now, KEY, 10);                // expires at now+10s
        assertFalse(CsrfGuard.verify(tok, KEY, now + 11_000));   // 11s later -> expired
    }

    @Test
    void tamperedOrWrongKeyRejected() {
        long now = 1_000_000L;
        String tok = CsrfGuard.mint(now, KEY, 3600);
        assertFalse(CsrfGuard.verify(tok, "different-key".getBytes(StandardCharsets.UTF_8), now));
        assertFalse(CsrfGuard.verify(tok + "x", KEY, now));      // tampered signature
        assertFalse(CsrfGuard.verify("garbage", KEY, now));
        assertFalse(CsrfGuard.verify(null, KEY, now));
    }

    @Test
    void crossInstanceTokensVerifyWithSharedSecret() {
        // two guards with the same configured secret accept each other's tokens (multi-instance)
        long now = 2_000_000L;
        String a = CsrfGuard.mint(now, KEY, 3600);
        assertTrue(CsrfGuard.verify(a, KEY, now + 5000)); // instance B (same KEY) accepts instance A's token
    }

    @Test
    void disabledGuardAlwaysValid() {
        CsrfGuard g = new CsrfGuard(); // enabled defaults false without Spring injection
        assertTrue(g.valid("anything"));
    }

    @Test
    void enabledGuardRequiresValidToken() throws Exception {
        CsrfGuard g = new CsrfGuard();
        set(g, "enabled", true);
        set(g, "ttlSeconds", 3600L);
        String tok = g.token();
        assertTrue(g.valid(tok));
        assertFalse(g.valid("wrong"));
        assertThrows(RuntimeException.class, () -> g.require("wrong"));
        g.require(tok); // valid token: no throw
    }

    private static void set(Object o, String field, Object val) throws Exception {
        var f = CsrfGuard.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, val);
    }
}
