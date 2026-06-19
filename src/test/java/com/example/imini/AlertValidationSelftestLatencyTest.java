package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config-validation warnings, latency-histogram bucketing, and self-test routing report. */
class AlertValidationSelftestLatencyTest {

    // ---- Feature 1: config validation (pure) ----

    @Test
    void disabledWithWebhookWarns() {
        var w = AlertSink.validateConfig(false, true, 0, 3, "", 0, 0, false, 0, true, true, 168, true, true);
        assertTrue(w.stream().anyMatch(s -> s.contains("alerts.enabled=false")));
    }

    @Test
    void enabledNoSinkWarns() {
        var w = AlertSink.validateConfig(true, false, 0, 3, "", 0, 0, false, 0, true, false, 168, true, true);
        assertTrue(w.stream().anyMatch(s -> s.contains("only be logged")));
    }

    @Test
    void tiersSetButUnparsedWarns() {
        var w = AlertSink.validateConfig(true, true, 0, 3, "garbage-no-pipe", 0, 0, false, 0, true, false, 168, true, true);
        assertTrue(w.stream().anyMatch(s -> s.contains("parsed to 0 tiers")));
    }

    @Test
    void dedupSharedNoDbWarns() {
        var w = AlertSink.validateConfig(true, true, 0, 3, "", 0, 0, false, 60, true, false, 168, true, false);
        assertTrue(w.stream().anyMatch(s -> s.contains("dedup falls back to per-process")));
    }

    @Test
    void persistentNoDbWarns() {
        var w = AlertSink.validateConfig(true, true, 0, 3, "", 0, 0, false, 0, false, false, 168, true, false);
        assertTrue(w.stream().anyMatch(s -> s.contains("in-memory ring")));
    }

    @Test
    void digestWithoutDedupWindowWarns() {
        var w = AlertSink.validateConfig(true, true, 0, 3, "", 0, 0, false, 0, false, true, 168, true, true);
        assertTrue(w.stream().anyMatch(s -> s.contains("dedup-digest=true has no effect")));
    }

    @Test
    void coherentConfigNoWarnings() {
        var w = AlertSink.validateConfig(true, true, 0, 3, "", 0, 0, false, 0, false, false, 168, true, true);
        assertTrue(w.isEmpty());
    }

    // ---- Feature 3: latency histogram bucketing (pure) ----

    @Test
    void bucketIndexBoundaries() {
        assertEquals(0, AlertSink.bucketIndex(0));
        assertEquals(0, AlertSink.bucketIndex(50));     // <= 50
        assertEquals(1, AlertSink.bucketIndex(51));     // <= 100
        assertEquals(3, AlertSink.bucketIndex(500));
        assertEquals(AlertSink.LATENCY_BUCKETS_MS.length, AlertSink.bucketIndex(999_999)); // +Inf
    }

    @Test
    void statsExposesDeliveryLatency() {
        Map<String, Object> stats = new AlertSink(null, null).stats();
        assertTrue(stats.containsKey("delivery_latency"));
        Object dl = stats.get("delivery_latency");
        assertTrue(dl instanceof Map);
        assertTrue(((Map<?, ?>) dl).containsKey("buckets"));
    }

    @Test
    void promRendersCumulativeHistogram() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        Map<String, Object> alerts = new java.util.LinkedHashMap<>();
        Map<String, Object> dl = new java.util.LinkedHashMap<>();
        Map<String, Long> buckets = new java.util.LinkedHashMap<>();
        buckets.put("50", 1L); buckets.put("100", 2L); buckets.put("+Inf", 1L);
        dl.put("buckets", buckets); dl.put("sum_ms", 400L); dl.put("count", 4L);
        alerts.put("delivery_latency", dl);
        snap.put("alerts", alerts);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_delivery_latency_ms_bucket{le=\"50\"} 1"));
        assertTrue(out.contains("imini_alerts_delivery_latency_ms_bucket{le=\"100\"} 3"));   // cumulative
        assertTrue(out.contains("imini_alerts_delivery_latency_ms_bucket{le=\"+Inf\"} 4"));
        assertTrue(out.contains("imini_alerts_delivery_latency_ms_sum 400"));
        assertTrue(out.contains("imini_alerts_delivery_latency_ms_count 4"));
    }

    // ---- Feature 2: self-test report (no DB, no send) ----

    @Test
    void selfTestReportsRoutingWithoutSend() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> r = s.selfTest("capability_denied", false);
        assertEquals("capability_denied", r.get("action"));
        for (String k : List.of("forwarded_action", "resolved_url", "routed", "template_used",
                "dedup_enabled", "would_deliver")) {
            assertTrue(r.containsKey(k), "missing " + k);
        }
        assertTrue(!r.containsKey("probe")); // send=false -> no probe
    }

    @Test
    void selfTestSendWhileDisabledReportsNoProbe() {
        AlertSink s = new AlertSink(null, null); // not enabled
        Map<String, Object> r = s.selfTest("spend_alert", true);
        Object probe = r.get("probe");
        assertTrue(probe instanceof Map);
        assertEquals(false, ((Map<?, ?>) probe).get("attempted"));
    }
}
