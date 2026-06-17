package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure title normalization + fork-name derivation for the session UX. */
class SessionNamingTest {

    @Test
    void cleanTitleTrimsCollapsesAndCaps() {
        assertEquals("a b c", SessionNaming.cleanTitle("  a\t b\n c  "));
        assertEquals("", SessionNaming.cleanTitle(null));
        assertEquals("", SessionNaming.cleanTitle("   "));
        assertEquals(SessionNaming.MAX_TITLE, SessionNaming.cleanTitle("x".repeat(200)).length());
    }

    @Test
    void forkTitlePrefersNameFallsBackToIdAndDoesNotStack() {
        assertEquals("fork of My work", SessionNaming.forkTitle("My work", "s1"));
        assertEquals("fork of sess-42", SessionNaming.forkTitle("", "sess-42"));
        assertEquals("fork of sess-42", SessionNaming.forkTitle(null, "sess-42"));
        // already a fork -> stays the same (no "fork of fork of ...")
        assertEquals("fork of My work", SessionNaming.forkTitle("fork of My work", "s1"));
    }

    @Test
    void displayNameUsesTitleElseId() {
        assertEquals("id7", SessionNaming.displayName("", "id7"));
        assertEquals("id7", SessionNaming.displayName(null, "id7"));
        assertEquals("Title", SessionNaming.displayName("Title", "id7"));
    }

    @Test
    void forkTitleCaseInsensitiveStackGuard() {
        assertTrue(SessionNaming.forkTitle("FORK OF X", "s").equalsIgnoreCase("fork of x"));
    }
}
