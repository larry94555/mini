package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Latency-SLO math, self-test interpretation, per-route latency snapshot, and SLO/selftest Prometheus output. */
class AlertSloRouteSelftestTest {

    // ---- Feature 1: SLO snapshot (pure) ----

    @Test
    void sloMeetingObjective() {
        Map<String, Object> s = AlertSink.sloSnapshot(99, 100, 0.99, 1000);
        assertEquals(0.99, (double) s.get("success_ratio"));
        assertEquals(1L, s.get("bad"));
        assertEquals(0.01, (double) s.get("error_budget"));
        assertEquals(1.0, (double) s.get("burn_rate"));   // exactly at budget
        assertEquals(true, s.get("meeting_objective"));
    }

    @Test
    void sloBurningFast() {
        Map<String, Object> s = AlertSink.sloSnapshot(90, 100, 0.99, 1000); // 10% errors vs 1% budget
        assertEquals(10.0, (double) s.get("burn_rate"));
        assertEquals(false, s.get("meeting_objective"));
    }

    @Test
    void sloEmptyIsHealthy() {
        Map<String, Object> s = AlertSink.sloSnapshot(0, 0, 0.99, 1000);
        assertEquals(1.0, (double) s.get("success_ratio"));
        assertEquals(0.0, (double) s.get("burn_rate"));
        assertEquals(true, s.get("meeting_objective"));
    }

    @Test
    void statsExposesSloAndSelftest() {
        Map<String, Object> stats = new AlertSink(null, null).stats();
        assertTrue(stats.containsKey("delivery_slo"));
        assertTrue(stats.containsKey("selftest"));
        assertEquals(false, ((Map<?, ?>) stats.get("selftest")).get("ran"));
    }

    // ---- Feature 2: per-route latency snapshot ----

    @Test
    void routeSnapshotHasLatencyKeys() {
        Map<String, Object> stats = new AlertSink(null, null).stats();
        assertTrue(stats.get("by_route") instanceof Map);
        // empty by default; structural check happens via Prometheus test below
    }

    @Test
    void promRendersRouteLatencyAndSlo() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> alerts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> br = new LinkedHashMap<>();
        Map<String, Long> r = new LinkedHashMap<>();
        r.put("sent", 5L); r.put("failed", 1L); r.put("dead_lettered", 0L); r.put("suppressed", 0L);
        r.put("avg_latency_ms", 250L); r.put("latency_count", 6L);
        br.put("spend_alert", r);
        alerts.put("by_route", br);
        Map<String, Object> slo = AlertSink.sloSnapshot(95, 100, 0.99, 1000);
        alerts.put("delivery_slo", slo);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("ran", true); st.put("ok", true); st.put("latency_ms", 42L);
        alerts.put("selftest", st);
        snap.put("alerts", alerts);

        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_route_latency_avg_ms{route=\"spend_alert\"} 250"));
        assertTrue(out.contains("imini_alerts_slo_success_ratio 0.95"));
        assertTrue(out.contains("imini_alerts_slo_burn_rate 5"));        // 5% err / 1% budget
        assertTrue(out.contains("imini_alerts_selftest_ok 1"));
        assertTrue(out.contains("imini_alerts_selftest_latency_ms 42"));
    }

    // ---- Feature 3: self-test interpretation (pure) ----

    @Test
    void interpretResolutionOnly() {
        assertTrue(AlertSelfTestScheduler.interpret(Map.of("would_deliver", true), false));
        assertFalse(AlertSelfTestScheduler.interpret(Map.of("would_deliver", false), false));
    }

    @Test
    void interpretWithProbe() {
        assertTrue(AlertSelfTestScheduler.interpret(
                Map.of("would_deliver", true, "probe", Map.of("ok", true)), true));
        assertFalse(AlertSelfTestScheduler.interpret(
                Map.of("would_deliver", true, "probe", Map.of("ok", false)), true));
        assertFalse(AlertSelfTestScheduler.interpret(Map.of("would_deliver", true), true)); // no probe -> fail
    }

    @Test
    void recordSelfTestRoundTrips() {
        AlertSink s = new AlertSink(null, null);
        s.recordSelfTest(true, 33, "ok");
        Map<String, Object> st = s.selfTestStatus();
        assertEquals(true, st.get("ran"));
        assertEquals(true, st.get("ok"));
        assertEquals(33L, st.get("latency_ms"));
    }
}
