package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drift-free SLO good/total counters, self-test flap detection + history, and per-route SLO. */
class AlertSloCountersFlapRouteTest {

    // ---- Feature 1: SLO good/total counters in Prometheus ----

    @Test
    void promExportsSloCounters() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("delivery_slo", AlertSink.sloSnapshot(950, 1000, 0.99, 1000));
        snap.put("alerts", alerts);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("# TYPE imini_alerts_slo_good_total counter"));
        assertTrue(out.contains("imini_alerts_slo_good_total 950"));
        assertTrue(out.contains("# TYPE imini_alerts_slo_total_total counter"));
        assertTrue(out.contains("imini_alerts_slo_total_total 1000"));
    }

    // ---- Feature 2: flap detection (pure) ----

    @Test
    void flapTransitionsCounts() {
        assertEquals(0, AlertSink.flapTransitions(List.of(true, true, true)));
        assertEquals(0, AlertSink.flapTransitions(List.of()));
        assertEquals(0, AlertSink.flapTransitions(List.of(true)));
        assertEquals(3, AlertSink.flapTransitions(List.of(true, false, true, false)));
        assertEquals(1, AlertSink.flapTransitions(List.of(true, true, false)));
    }

    @Test
    void isFlappingThreshold() {
        assertTrue(AlertSink.isFlapping(3, 3));
        assertTrue(AlertSink.isFlapping(5, 3));
        assertFalse(AlertSink.isFlapping(2, 3));
        assertFalse(AlertSink.isFlapping(10, 0)); // threshold 0 disables
    }

    @Test
    void selfTestReportTracksHistoryAndFlap() {
        AlertSink s = new AlertSink(null, null);
        // default flap threshold is 0 without Spring injection; exercise history + transitions only
        s.recordSelfTest(true, 10, "ok");
        s.recordSelfTest(false, 20, "bad");
        s.recordSelfTest(true, 15, "ok");
        Map<String, Object> r = s.selfTestReport();
        assertEquals(3, r.get("runs"));
        assertEquals(2, r.get("transitions"));
        assertTrue(r.get("history") instanceof List);
        assertTrue(((Map<?, ?>) r.get("last")).get("ok").equals(true));
    }

    @Test
    void historyBoundedTo20() {
        AlertSink s = new AlertSink(null, null);
        for (int i = 0; i < 30; i++) s.recordSelfTest(i % 2 == 0, i, "r" + i);
        Map<String, Object> r = s.selfTestReport();
        assertEquals(20, r.get("runs"));
    }

    // ---- Feature 3: per-route SLO ----

    @Test
    void sloByRouteEmptyWithoutTraffic() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.sloByRoute().isEmpty());
        assertTrue(s.stats().containsKey("slo_by_route"));
    }

    @Test
    void promExportsPerRouteSlo() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> alerts = new LinkedHashMap<>();
        Map<String, Map<String, Object>> sbr = new LinkedHashMap<>();
        sbr.put("spend_alert", AlertSink.sloSnapshot(8, 10, 0.99, 1000)); // 80% -> burn 20
        alerts.put("slo_by_route", sbr);
        snap.put("alerts", alerts);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_route_slo_success_ratio{route=\"spend_alert\"} 0.8"));
        assertTrue(out.contains("imini_alerts_route_slo_burn_rate{route=\"spend_alert\"} 20"));
        assertTrue(out.contains("imini_alerts_route_slo_good_total{route=\"spend_alert\"} 8"));
        assertTrue(out.contains("imini_alerts_route_slo_total_total{route=\"spend_alert\"} 10"));
    }
}
