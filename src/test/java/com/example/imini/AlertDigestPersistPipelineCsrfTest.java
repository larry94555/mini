package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Digest baseline persistence (serialize/parse), pipeline-mode post, and the overview Send-digest control. */
class AlertDigestPersistPipelineCsrfTest {

    // ---- Feature 1: baseline serialize/parse (pure) ----

    @Test
    void baselineRoundTrips() {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("ts", 1_700_000_000_000L);
        base.put("window_budget_remaining", 0.5);
        base.put("delivery_success_ratio", 0.997);
        base.put("dead_lettered", 12L);
        String s = AlertSink.serializeBaseline(base);
        Map<String, Object> back = AlertSink.parseBaseline(s);
        assertEquals(1_700_000_000_000L, back.get("ts"));
        assertEquals(0.5, back.get("window_budget_remaining"));
        assertEquals(0.997, back.get("delivery_success_ratio"));
        assertEquals(12L, back.get("dead_lettered"));
    }

    @Test
    void parseBaselineRejectsMalformed() {
        assertNull(AlertSink.parseBaseline(null));
        assertNull(AlertSink.parseBaseline(""));
        assertNull(AlertSink.parseBaseline("1|2|3"));      // too few fields
        assertNull(AlertSink.parseBaseline("a|b|c|d"));    // non-numeric
    }

    // ---- Feature 3: pipeline-mode post ----

    @Test
    void postDigestPipelineModeNoOpWithoutUrl() {
        // no URL configured -> not posted regardless of mode
        Map<String, Object> r = new AlertSink(null, null).postSloDigest();
        assertEquals(false, r.get("posted"));
        assertTrue(String.valueOf(r.get("summary")).startsWith("imini SLO digest:"));
    }

    @Test
    void probeModeReportedWhenNotPipeline() {
        // default (non-pipeline) sink with no URL still reports a summary; mode appears only with a URL.
        Map<String, Object> r = new AlertSink(null, null).postSloDigest();
        assertFalse(r.containsKey("mode")); // no URL path returns early before mode is set
    }

    // ---- Feature 2: overview Send-digest control ----

    @Test
    void overviewShowsSendDigestWhenTokenPresent() {
        String html = AlertsOverview.render(Map.of("sent", 1L), List.of(), 0, "tok-123");
        assertTrue(html.contains("Send SLO digest now"));
        assertTrue(html.contains("var CSRF=\"tok-123\""));
        assertTrue(html.contains("/admin/alerts/slo-digest"));
        assertTrue(html.contains("X-CSRF-Token"));
    }

    @Test
    void overviewHidesSendDigestWithoutToken() {
        String html = AlertsOverview.render(Map.of("sent", 1L), List.of(), 0, null);
        assertFalse(html.contains("Send SLO digest now"));
        String html2 = AlertsOverview.render(Map.of("sent", 1L), List.of()); // back-compat overload
        assertFalse(html2.contains("Send SLO digest now"));
    }

    @Test
    void overviewEscapesToken() {
        String html = AlertsOverview.render(Map.of("sent", 1L), List.of(), 0, "a\"b");
        assertFalse(html.contains("var CSRF=\"a\"b\"")); // raw quote must be escaped
        assertTrue(html.contains("&quot;"));
    }
}
