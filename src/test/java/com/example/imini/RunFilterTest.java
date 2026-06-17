package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure run-history filter: endpoint/outcome/session matching. */
class RunFilterTest {

    private static RunHistory.Record rec(String endpoint, String session, boolean ok) {
        return new RunHistory.Record(1L, endpoint, session, "auto", 10L, ok);
    }

    @Test
    void endpointSubstringCaseInsensitiveBlankAny() {
        RunHistory.Record r = rec("/chat/stream", "s1", true);
        assertTrue(RunFilter.matches(r, "chat", "", ""));
        assertTrue(RunFilter.matches(r, "CHAT", "", ""));   // case-insensitive
        assertTrue(RunFilter.matches(r, "", "", ""));        // blank -> any
        assertFalse(RunFilter.matches(r, "ask", "", ""));
    }

    @Test
    void outcomeFilter() {
        RunHistory.Record ok = rec("/ask", "s", true);
        RunHistory.Record fail = rec("/ask", "s", false);
        assertTrue(RunFilter.matches(ok, "", "ok", ""));
        assertFalse(RunFilter.matches(ok, "", "failed", ""));
        assertTrue(RunFilter.matches(fail, "", "failed", ""));
        assertTrue(RunFilter.matches(ok, "", "anything-else", "")); // unknown -> any
        assertTrue(RunFilter.outcomeOk(true, "success"));            // aliases
        assertTrue(RunFilter.outcomeOk(false, "error"));
    }

    @Test
    void sessionSubstringAndNullSafety() {
        RunHistory.Record r = rec("/chat", "proj-42", true);
        assertTrue(RunFilter.matches(r, "", "", "proj"));
        assertTrue(RunFilter.matches(r, "", "", "PROJ-42"));
        assertFalse(RunFilter.matches(r, "", "", "other"));
        assertFalse(RunFilter.matches(null, "", "", ""));
    }

    @Test
    void sessionEqualsIsExactCaseInsensitiveAndNullSafe() {
        assertTrue(RunFilter.sessionEquals("proj-1", "proj-1"));
        assertTrue(RunFilter.sessionEquals("PROJ-1", "proj-1")); // case-insensitive
        assertFalse(RunFilter.sessionEquals("proj-12", "proj-1")); // exact, not substring
        assertFalse(RunFilter.sessionEquals(null, "proj"));
        assertFalse(RunFilter.sessionEquals("x", null));
    }
}
