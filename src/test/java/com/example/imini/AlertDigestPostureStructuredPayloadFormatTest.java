package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Current-posture overview row, structured webhook payload, and JSON/CSV report link/download choice. */
class AlertDigestPostureStructuredPayloadFormatTest {

    // ---- Feature 2: structured webhook payload ----

    @Test
    void payloadKeepsTextAndAddsStructuredDigest() {
        Map<String, Object> digest = new java.util.LinkedHashMap<>();
        digest.put("window_success_ratio", 0.985);
        digest.put("delivery_success_ratio", 0.997);
        digest.put("muted", false);
        digest.put("worst_route", "spend_alert");
        String json = AlertSink.digestPayloadJson("imini SLO digest: window 98%", digest);
        assertTrue(json.startsWith("{\"text\":\""));                  // back-compat text field first
        assertTrue(json.contains("\"digest\":{"));                    // structured object present
        assertTrue(json.contains("\"window_success_ratio\":0.985"));  // number unquoted
        assertTrue(json.contains("\"muted\":false"));                 // boolean unquoted
        assertTrue(json.contains("\"worst_route\":\"spend_alert\"")); // string quoted
    }

    @Test
    void payloadTextOnlyWhenNoDigest() {
        assertTrue(AlertSink.digestPayloadJson("hi", null).equals("{\"text\":\"hi\"}"));
        assertTrue(AlertSink.digestPayloadJson("hi", Map.of()).equals("{\"text\":\"hi\"}"));
    }

    @Test
    void payloadEscapesTextAndSkipsNaN() {
        String json = AlertSink.digestPayloadJson("a \"quote\"", new java.util.LinkedHashMap<>(Map.of("r", Double.NaN)));
        assertTrue(json.contains("\\\"quote\\\""));   // text escaped
        assertTrue(json.contains("\"r\":null"));      // NaN -> null
    }

    // ---- Feature 1: posture row ----

    @Test
    void postureRowRendersFromSnapshot() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("window_success_ratio", 0.985); snap.put("slo_target", 0.99);
        snap.put("delivery_success_ratio", 0.997); snap.put("success_target", 0.99);
        snap.put("worst_route", "spend_alert"); snap.put("worst_route_ratio", 0.95);
        snap.put("muted", true); snap.put("catchup", false);
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("digest_snapshot", snap);
        String row = AlertsOverview.postureRow(stats);
        assertTrue(row.contains("digest_posture"));
        assertTrue(row.contains("window 98.5% / 99%"));
        assertTrue(row.contains("delivery 99.7% / 99%"));
        assertTrue(row.contains("worst latency: spend_alert"));
        assertTrue(row.contains("muted"));
    }

    @Test
    void postureRowEmptyWithoutSnapshot() {
        assertTrue(AlertsOverview.postureRow(new java.util.LinkedHashMap<>()).isEmpty());
    }

    @Test
    void overviewIncludesPostureRow() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("window_success_ratio", 0.98); snap.put("delivery_success_ratio", 0.99);
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of());
        stats.put("digest_snapshot", snap);
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("id=\"digest_posture\""));
    }

    // ---- Feature 3: JSON/CSV report choice ----

    @Test
    void overviewHasJsonCsvReportChoice() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of());
        stats.put("digest_snapshot", Map.of("window_success_ratio", 0.98));
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("downloadDigestReport('csv')"));
        assertTrue(html.contains("downloadDigestReport('json')"));
        assertTrue(html.contains("copyDigestLink('json')"));
        assertTrue(html.contains("copyDigestLink('csv')"));
        assertTrue(html.contains("function downloadDigestReport("));
        assertTrue(html.contains("fmt==='csv'?'&format=csv':''")); // JSON omits format, CSV adds it
    }
}
