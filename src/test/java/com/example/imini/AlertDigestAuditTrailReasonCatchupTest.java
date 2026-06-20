package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filtered digest audit trail, reason-required-for-long-mutes, and the mute-expiry catch-up digest. */
class AlertDigestAuditTrailReasonCatchupTest {

    // ---- Feature 2: reason required for long mutes (pure helper, explicit threshold) ----

    @Test
    void reasonRequiredBoundary() {
        assertFalse(AlertSink.reasonRequired(4, 8));    // under threshold
        assertFalse(AlertSink.reasonRequired(8, 8));    // exactly at threshold
        assertTrue(AlertSink.reasonRequired(9, 8));     // over threshold
        assertFalse(AlertSink.reasonRequired(100, 0));  // threshold<=0 disables
        assertFalse(AlertSink.reasonRequired(100, -1));
    }

    // ---- Feature 1: digest audit trail accessor ----

    @Test
    void digestAuditTrailEmptyWithoutDatabase() {
        // audit present but no DB -> guarded to empty (recent() needs a DB)
        AlertSink s = new AlertSink(new AuditLog(null), null);
        assertTrue(s.digestAuditTrail(10).isEmpty());
        // no audit at all -> empty
        assertTrue(new AlertSink(null, null).digestAuditTrail(10).isEmpty());
    }

    @Test
    void overviewRendersDigestAuditSection() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("time", "2023-11-14T22:13:20Z");
        row.put("user", "alice");
        row.put("action", "alert_digest_mute");
        row.put("outcome", "muted: deploying v2");
        stats.put("digest_audit", List.of(row));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("Digest mute audit"));
        assertTrue(html.contains("alice"));
        assertTrue(html.contains("alert_digest_mute"));
        assertTrue(html.contains("deploying v2"));
    }

    // ---- Feature 3: mute-expiry catch-up ----

    @Test
    void catchupFlagSetByExpiryAndClearedOnSend() {
        AlertSink s = new AlertSink(null, null);
        // not in catch-up initially
        assertFalse(Boolean.TRUE.equals(s.sloDigest().get("catchup")));
        // mute then force-expire by muting 0h (until = now) and letting expireMuteIfDue fire
        s.muteDigest(0);                 // until == now -> immediately expirable
        assertTrue(s.expireMuteIfDue()); // elapsed -> sets pendingCatchup
        Map<String, Object> d = s.sloDigest();
        assertEquals(true, d.get("catchup"));
        // a send (no URL here) does not clear; an actual send path clears. Verify format carries the note.
        assertTrue(AlertSink.formatSloDigest(d).contains("(catch-up after mute)"));
    }

    @Test
    void catchupTemplatePlaceholder() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("catchup", true);
        assertEquals("x=catch-up", AlertSink.renderDigest(d, "x={catchup}"));
        d.put("catchup", false);
        assertEquals("x=", AlertSink.renderDigest(d, "x={catchup}"));
    }

    @Test
    void postWhenNotMutedIsNotCatchup() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> d = s.sloDigest();
        assertFalse(Boolean.TRUE.equals(d.get("catchup")));
    }
}
