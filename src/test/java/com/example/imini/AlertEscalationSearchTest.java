package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared-dedup pure outcome, escalation/ack gating, and dead-letter search filter (no DB). */
class AlertEscalationSearchTest {

    private static AlertSink.DeadLetter dl(String action, String status, String payload) {
        return new AlertSink.DeadLetter("id", 1L, payload, "u", 1, "err", status, 0L, action);
    }

    @Test
    void dedupOutcomeFreshForwardsAndResets() {
        AlertSink.DedupOutcome o = AlertSink.dedupOutcome(-1, 0, 1000, 10_000);
        assertTrue(o.forward());
        assertEquals(1000, o.newWindowStart());
        assertEquals(0, o.newSuppressed());
        assertEquals(0, o.suppressedSincePrev());
    }

    @Test
    void dedupOutcomeWithinWindowSuppresses() {
        AlertSink.DedupOutcome o = AlertSink.dedupOutcome(1000, 2, 3000, 10_000);
        assertFalse(o.forward());
        assertEquals(1000, o.newWindowStart()); // unchanged
        assertEquals(3, o.newSuppressed());     // incremented
    }

    @Test
    void dedupOutcomeElapsedReopensReportingPriorSuppressed() {
        AlertSink.DedupOutcome o = AlertSink.dedupOutcome(1000, 5, 1000 + 10_000, 10_000);
        assertTrue(o.forward());
        assertEquals(11_000, o.newWindowStart());
        assertEquals(0, o.newSuppressed());
        assertEquals(5, o.suppressedSincePrev());
    }

    @Test
    void escalationDisabledByDefault() {
        AlertSink s = new AlertSink(null, null);
        assertFalse(s.escalationEnabled());        // no escalate-url / threshold
        assertEquals(0, s.escalateStale(System.currentTimeMillis()));
        assertEquals(0, s.ack("anything"));        // no DB
    }

    @Test
    void searchFilterMatchesActionStatusAndPayload() {
        AlertSink.DeadLetter d = dl("spend_alert", "failed", "{\"action\":\"spend_alert\",\"target\":\"acct-9\"}");
        assertTrue(AlertSink.matchesFilter(d, "spend_alert", null, null));
        assertTrue(AlertSink.matchesFilter(d, null, "failed", null));
        assertTrue(AlertSink.matchesFilter(d, null, null, "acct-9"));
        assertFalse(AlertSink.matchesFilter(d, "capability_denied", null, null)); // wrong action
        assertFalse(AlertSink.matchesFilter(d, null, "acked", null));             // wrong status
        assertFalse(AlertSink.matchesFilter(d, null, null, "acct-1"));            // payload miss
    }

    @Test
    void searchFilterStatusCaseInsensitive() {
        AlertSink.DeadLetter d = dl("x", "FAILED", "p");
        assertTrue(AlertSink.matchesFilter(d, null, "failed", null));
    }

    @Test
    void deadLetterPageEmptyWithoutDb() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.deadLetterPage(null, null, null, 0, 50).isEmpty());
        assertEquals(0, s.deadLetterCount(null, null, null));
    }

    @Test
    void statsExposesEscalatedAndRouteSuppressed() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.stats().containsKey("escalated"));
        assertTrue(s.stats().containsKey("by_route"));
    }
}
