package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live-refreshed posture row, the structured-payload toggle, and Prometheus posture gauges. */
class AlertDigestLivePostureToggleGaugesTest {

    // ---- Feature 1: live posture rebuild ----

    @Test
    void liveScriptDefinesAndRebuildsPosture() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("recent_digests", List.of());
        stats.put("digest_snapshot", Map.of("window_success_ratio", 0.98));
        String html = AlertsOverview.render(stats, List.of(), 5); // autoRefresh on -> live script present
        assertTrue(html.contains("function postureHtml("));
        assertTrue(html.contains("pb.innerHTML=postureHtml(s.digest_snapshot)"));
        // the JS mirrors the server pills
        assertTrue(html.contains("worst latency: "));
        assertTrue(html.contains("catch-up pending"));
    }

    // ---- Feature 2: structured-payload toggle ----

    @Test
    void toggleOnIncludesDigestOffOmitsIt() {
        Map<String, Object> digest = new java.util.LinkedHashMap<>(Map.of("window_success_ratio", 0.98, "muted", false));
        String on = AlertSink.digestPayloadJson("hi", digest, true);
        assertTrue(on.contains("\"digest\":{"));
        assertTrue(on.contains("\"window_success_ratio\":0.98"));
        String off = AlertSink.digestPayloadJson("hi", digest, false);
        assertTrue(off.equals("{\"text\":\"hi\"}"));   // structured object omitted
        assertFalse(off.contains("\"digest\":"));
    }

    // ---- Feature 3: Prometheus posture gauges ----

    @Test
    void promExportsPostureGauges() {
        Map<String, Object> alerts = new java.util.LinkedHashMap<>();
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("window_success_ratio", 0.985);
        snap.put("delivery_success_ratio", 0.997);
        snap.put("worst_route_ratio", 0.95);
        snap.put("worst_success_route_ratio", 0.96);
        alerts.put("digest_snapshot", snap);
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("alerts", alerts);
        String prom = PromFormat.render(stats);
        assertTrue(prom.contains("imini_alerts_digest_window_ratio 0.985"));
        assertTrue(prom.contains("imini_alerts_digest_delivery_ratio 0.997"));
        assertTrue(prom.contains("imini_alerts_digest_worst_route_ratio 0.95"));
        assertTrue(prom.contains("imini_alerts_digest_worst_success_route_ratio 0.96"));
        assertTrue(prom.contains("# TYPE imini_alerts_digest_window_ratio gauge"));
    }

    @Test
    void promSkipsPostureGaugesWhenAbsentOrNaN() {
        Map<String, Object> alerts = new java.util.LinkedHashMap<>();
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        snap.put("window_success_ratio", Double.NaN); // non-finite -> skipped
        alerts.put("digest_snapshot", snap);
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("alerts", alerts);
        String prom = PromFormat.render(stats);
        assertFalse(prom.contains("imini_alerts_digest_window_ratio"));   // NaN skipped
        assertFalse(prom.contains("imini_alerts_digest_delivery_ratio")); // absent skipped
    }
}
