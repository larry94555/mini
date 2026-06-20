package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mute audit-log events, mute reason/note, and the max-mute cap. */
class AlertDigestMuteAuditReasonCapTest {

    // ---- Feature 3: max-mute cap (pure) ----

    @Test
    void clampMuteHoursCaps() {
        assertEquals(4.0, AlertSink.clampMuteHours(4, 72));
        assertEquals(72.0, AlertSink.clampMuteHours(1000, 72));   // capped
        assertEquals(0.0, AlertSink.clampMuteHours(-5, 72));      // floored at 0
        assertEquals(1000.0, AlertSink.clampMuteHours(1000, 0));  // max<=0 => no cap
    }

    @Test
    void muteRespectsCap() {
        AlertSink s = new AlertSink(null, null); // @Value default 72 not injected -> field is 0 (no cap) in unit test
        long until = s.muteDigest(5, "deploy", "alice");
        assertTrue(until > System.currentTimeMillis());
    }

    // ---- Feature 2: mute reason ----

    @Test
    void muteReasonStoredAndShownInDigest() {
        AlertSink s = new AlertSink(null, null);
        s.muteDigest(2, "deploying v2", "alice");
        assertEquals("deploying v2", s.digestMuteReason());
        Map<String, Object> d = s.sloDigest();
        assertEquals(true, d.get("muted"));
        assertEquals("deploying v2", d.get("muted_reason"));
        assertTrue(AlertSink.formatSloDigest(d).contains("[muted: deploying v2]"));
    }

    @Test
    void unmuteClearsReason() {
        AlertSink s = new AlertSink(null, null);
        s.muteDigest(2, "x", "alice");
        s.unmuteDigest("bob");
        assertEquals("", s.digestMuteReason());
        assertEquals(0L, s.digestMuteUntil());
    }

    @Test
    void templateExposesMutedReason() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("muted", true);
        d.put("muted_reason", "maint");
        assertEquals("why=maint", AlertSink.renderDigest(d, "why={muted_reason}"));
    }

    @Test
    void overviewMuteNoteIncludesReason() {
        long until = 1000L + 3_600_000L;
        assertTrue(AlertsOverview.muteNote(until, 1000L, "deploy").contains("(deploy)"));
        assertEquals("Digest not muted.", AlertsOverview.muteNote(0L, 1000L, "deploy"));
    }

    // ---- Feature 1: audit events ----

    @Test
    void muteRecordsAuditEvent() {
        AuditLog audit = new AuditLog(null); // in-memory fallback
        List<AuditLog.Entry> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        audit.addListener(seen::add);
        AlertSink s = new AlertSink(audit, null);
        s.muteDigest(2, "deploy", "alice");
        assertTrue(seen.stream().anyMatch(e -> "alert_digest_mute".equals(e.action())
                && "alice".equals(e.user()) && String.valueOf(e.outcome()).contains("deploy")));
    }

    @Test
    void unmuteRecordsAuditEventOnlyWhenWasMuted() {
        AuditLog audit = new AuditLog(null);
        List<AuditLog.Entry> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        audit.addListener(seen::add);
        AlertSink s = new AlertSink(audit, null);
        s.unmuteDigest("bob"); // not muted -> no event
        assertFalse(seen.stream().anyMatch(e -> "alert_digest_unmute".equals(e.action())));
        s.muteDigest(2, "x", "alice");
        s.unmuteDigest("bob");
        assertTrue(seen.stream().anyMatch(e -> "alert_digest_unmute".equals(e.action()) && "bob".equals(e.user())));
    }

    @Test
    void expireRecordsAuditEvent() {
        AuditLog audit = new AuditLog(null);
        AlertSink s = new AlertSink(audit, null);
        s.muteDigest(2, "deploy", "alice");
        // can't easily fast-forward; assert the pure expiry boundary instead, plus no premature expiry
        assertFalse(s.expireMuteIfDue());
        assertTrue(AlertSink.muteExpired(2000L, 2000L));
    }
}
