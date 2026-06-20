package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mute state shown in the digest + Prometheus, and auto-expiring mute with a logged resumption. */
class AlertDigestMuteObservabilityTest {

    // ---- Feature 1: mute state in the digest ----

    @Test
    void sloDigestCarriesMuteState() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> d0 = s.sloDigest();
        assertEquals(false, d0.get("muted"));
        assertEquals(0L, d0.get("muted_until"));
        s.muteDigest(2);
        Map<String, Object> d1 = s.sloDigest();
        assertEquals(true, d1.get("muted"));
        assertTrue(((Number) d1.get("muted_until")).longValue() > System.currentTimeMillis());
    }

    @Test
    void formatDigestShowsMutedPrefix() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("window_success_ratio", 0.99);
        d.put("window_budget_remaining", 1.0);
        d.put("delivery_success_ratio", 1.0);
        d.put("muted", true);
        assertTrue(AlertSink.formatSloDigest(d).startsWith("imini SLO digest: [muted] "));
        d.put("muted", false);
        assertFalse(AlertSink.formatSloDigest(d).contains("[muted]"));
    }

    @Test
    void templateExposesMutedPlaceholder() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("muted", true);
        assertEquals("state=muted", AlertSink.renderDigest(d, "state={muted}"));
        d.put("muted", false);
        assertEquals("state=", AlertSink.renderDigest(d, "state={muted}"));
    }

    // ---- Feature 1/3: mute state in Prometheus ----

    @Test
    void promExportsMuteGauges() {
        long future = System.currentTimeMillis() + 3_600_000L;
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("alerts", new java.util.LinkedHashMap<>(Map.of("digest_muted_until", future)));
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_digest_muted 1"));
        assertTrue(out.contains("imini_alerts_digest_mute_until_seconds " + (future / 1000L)));
    }

    @Test
    void promMuteGaugeZeroWhenNotMuted() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("alerts", new java.util.LinkedHashMap<>(Map.of("digest_muted_until", 0L)));
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_digest_muted 0"));
        assertTrue(out.contains("imini_alerts_digest_mute_until_seconds 0"));
    }

    // ---- Feature 2: auto-expire with resumption ----

    @Test
    void muteExpiredIsPureBoundary() {
        assertFalse(AlertSink.muteExpired(1000L, 0L));      // never muted
        assertFalse(AlertSink.muteExpired(1000L, 2000L));   // still muted
        assertTrue(AlertSink.muteExpired(2000L, 2000L));    // boundary = expired
        assertTrue(AlertSink.muteExpired(3000L, 2000L));    // past
    }

    @Test
    void expireMuteIfDueClearsAndReports() {
        AlertSink s = new AlertSink(null, null);
        assertFalse(s.expireMuteIfDue()); // nothing muted
        s.muteDigest(2);
        assertFalse(s.expireMuteIfDue()); // still in the future
        assertTrue(s.digestMuteUntil() > 0);
    }

    @Test
    void postAfterExpiryResumes() {
        AlertSink s = new AlertSink(null, null);
        s.muteDigest(2);
        // a muted post is suppressed
        assertEquals("muted", s.postSloDigest().get("mode"));
        // force past it: digest goes out and reports not-muted state
        Map<String, Object> f = s.postSloDigest(true);
        assertFalse("muted".equals(f.get("mode")));
    }
}
