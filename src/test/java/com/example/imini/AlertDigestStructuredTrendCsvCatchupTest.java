package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structured (v2) digest history rows + trend chart, digest-audit CSV, and the catch-up audit event. */
class AlertDigestStructuredTrendCsvCatchupTest {

    // ---- Feature 1: structured history rows ----

    @Test
    void v2RowRoundTripsWithMetricsAndPipes() {
        String summary = "imini SLO digest: window 98% worst latency a|b @ 90%";
        String s = AlertSink.serializeDigestHistory(1_700_000_000_000L, true, "probe", summary, 0.985, 0.997, 0.5);
        assertTrue(s.startsWith("v2|"));
        Map<String, Object> r = AlertSink.parseDigestHistory(s);
        assertEquals(1_700_000_000_000L, r.get("ts"));
        assertEquals(true, r.get("posted"));
        assertEquals("probe", r.get("mode"));
        assertEquals(0.985, r.get("window_ratio"));
        assertEquals(0.997, r.get("delivery_success"));
        assertEquals(0.5, r.get("budget_remaining"));
        assertEquals(summary, r.get("summary")); // tail preserved despite '|'
    }

    @Test
    void legacyRowStillParses() {
        // old 4-field format (no v2 prefix, no metrics) still readable
        Map<String, Object> r = AlertSink.parseDigestHistory("1700000000000|true|probe|some summary");
        assertEquals(1_700_000_000_000L, r.get("ts"));
        assertEquals("some summary", r.get("summary"));
        assertFalse(r.containsKey("delivery_success")); // legacy carries no metrics
    }

    @Test
    void v2RowOmitsNanMetrics() {
        String s = AlertSink.serializeDigestHistory(1000L, false, "muted", "x"); // 4-arg shim => NaN metrics
        assertTrue(s.startsWith("v2|"));
        Map<String, Object> r = AlertSink.parseDigestHistory(s);
        assertFalse(r.containsKey("window_ratio"));
        assertFalse(r.containsKey("delivery_success"));
        assertEquals("x", r.get("summary"));
    }

    @Test
    void malformedRowsRejected() {
        assertNull(AlertSink.parseDigestHistory(null));
        assertNull(AlertSink.parseDigestHistory(""));
        assertNull(AlertSink.parseDigestHistory("v2|notalong|true|probe|0.9|0.9|0.5|s"));
        assertNull(AlertSink.parseDigestHistory("v2|123|true|probe")); // too few v2 fields
    }

    // ---- Feature 2: digest-audit CSV ----

    @Test
    void digestAuditCsvQuotesAndHeads() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("time", "2023-11-14T22:13:20Z");
        row.put("user", "alice");
        row.put("action", "alert_digest_mute");
        row.put("target", "until:123");
        row.put("outcome", "muted: deploy, now"); // comma forces quoting
        String csv = AlertSink.digestAuditCsv(List.of(row));
        assertTrue(csv.startsWith("time,user,action,target,outcome\n"));
        assertTrue(csv.contains("\"muted: deploy, now\""));
        assertTrue(csv.contains("alert_digest_mute"));
    }

    @Test
    void digestAuditCsvEmptyIsHeaderOnly() {
        assertEquals("time,user,action,target,outcome\n", AlertSink.digestAuditCsv(List.of()));
    }

    // ---- Feature 1 (trend) is rendered in the overview ----

    @Test
    void overviewRendersDigestTrend() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Object> d1 = new java.util.LinkedHashMap<>();
        d1.put("time", "t1"); d1.put("posted", true); d1.put("mode", "probe");
        d1.put("delivery_success", 0.99); d1.put("summary", "a");
        Map<String, Object> d2 = new java.util.LinkedHashMap<>();
        d2.put("time", "t2"); d2.put("posted", true); d2.put("mode", "probe");
        d2.put("delivery_success", 0.97); d2.put("summary", "b");
        stats.put("recent_digests", List.of(d1, d2));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("delivery-success"));
        assertTrue(html.contains("digest_trendbox"));
    }

    // ---- Feature 3: catch-up audit event ----

    @Test
    void catchupAuditEventRecordedOnSend() {
        AuditLog audit = new AuditLog(null);
        List<AuditLog.Entry> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        audit.addListener(seen::add);
        AlertSink s = new AlertSink(audit, null);
        s.muteDigest(0);                 // until == now
        assertTrue(s.expireMuteIfDue()); // sets pendingCatchup
        // no URL configured -> not actually sent -> catch-up audit event NOT recorded yet
        s.postSloDigest();
        assertFalse(seen.stream().anyMatch(e -> "alert_digest_catchup".equals(e.action())));
        // the mute-expiry itself is audited
        assertTrue(seen.stream().anyMatch(e -> "alert_digest_mute_expired".equals(e.action())));
    }
}
