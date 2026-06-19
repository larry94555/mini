package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SLA aggregator, dedup summary fallback, digest panel render, and stats keys. */
class AlertSlaDigestPanelTest {

    @Test
    void aggregateSlaComputesCountAvgMax() {
        var samples = List.of(
                new long[]{1, 100}, new long[]{1, 300}, // tier 1: avg 200, max 300
                new long[]{2, 1000},                    // tier 2: avg/max 1000
                new long[]{0, 50},                      // tier 0 ignored
                new long[]{1, -5});                     // negative ignored
        Map<String, Map<String, Long>> out = AlertSink.aggregateSla(samples);
        assertEquals(2L, out.get("1").get("count"));
        assertEquals(200L, out.get("1").get("avg_ms"));
        assertEquals(300L, out.get("1").get("max_ms"));
        assertEquals(1L, out.get("2").get("count"));
        assertEquals(1000L, out.get("2").get("avg_ms"));
        assertTrue(!out.containsKey("0"));
    }

    @Test
    void aggregateSlaEmpty() {
        assertTrue(AlertSink.aggregateSla(List.of()).isEmpty());
        assertTrue(AlertSink.aggregateSla(null).isEmpty());
    }

    @Test
    void statsExposesSlaAndDedupSummaryEmptyWithoutDb() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.stats().containsKey("ack_sla_by_tier"));
        assertTrue(s.ackSlaByTier().isEmpty());
        assertTrue(s.dedupSummary(10).isEmpty()); // no in-memory suppressions yet
    }

    @Test
    void digestPanelRendersInViewer() {
        var digests = List.of(
                new AlertSink.DedupSummary("capability_denied|tool:run", "capability_denied", "tool:run", 12, 0L),
                new AlertSink.DedupSummary("spend_alert|acct-9", "spend_alert", "acct-9", 3, 0L));
        String html = DeadLetterDashboard.render(List.of(), "", "", "", 0, 50, 0, "tok123", digests);
        assertTrue(html.contains("Top suppressed keys"));
        assertTrue(html.contains("capability_denied"));
        assertTrue(html.contains(">12<"));         // suppressed count cell
        assertTrue(html.contains("var CSRF=\"tok123\""));  // token embedded
        assertTrue(html.contains("X-CSRF-Token")); // act() sends it
    }

    @Test
    void viewerOmitsPanelWhenNoDigests() {
        String html = DeadLetterDashboard.render(List.of(), "", "", "", 0, 50, 0, "t", List.of());
        assertTrue(!html.contains("Top suppressed keys"));
    }
}
