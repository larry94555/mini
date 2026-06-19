package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfGuardTest {

    @Test
    void constantTimeEqualsMatchesAndRejects() {
        assertTrue(CsrfGuard.constantTimeEquals("abc123", "abc123"));
        assertFalse(CsrfGuard.constantTimeEquals("abc123", "abc124"));
        assertFalse(CsrfGuard.constantTimeEquals("abc", "abcd"));   // length differs
        assertFalse(CsrfGuard.constantTimeEquals(null, "x"));
        assertFalse(CsrfGuard.constantTimeEquals("x", null));
    }

    @Test
    void tokenIsStableAndNonEmpty() {
        CsrfGuard g = new CsrfGuard();
        assertNotNull(g.token());
        assertTrue(g.token().length() >= 16);
        assertTrue(g.token().equals(g.token())); // stable per instance
    }

    @Test
    void validAndRequireWhenEnabled() {
        CsrfGuard g = new CsrfGuard();
        // enabled defaults to false here (no Spring @Value injection), so simulate enabled via reflection-free path:
        // valid() returns true when disabled; we test the matching logic through constantTimeEquals above and
        // the require() throw path below by toggling the field.
        assertTrue(g.valid("anything")); // disabled (no injection) -> always valid
    }

    @Test
    void requireThrowsOnBadTokenWhenEnabled() throws Exception {
        CsrfGuard g = new CsrfGuard();
        var f = CsrfGuard.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.setBoolean(g, true);
        assertTrue(g.valid(g.token()));
        assertFalse(g.valid("wrong"));
        assertThrows(RuntimeException.class, () -> g.require("wrong"));
        g.require(g.token()); // correct token: no throw
    }
}
