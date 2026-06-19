package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromFormatRouteTest {

    @Test
    void rendersPerRouteCountersAndSuppressed() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("sent", 7L);
        alerts.put("suppressed", 4L);
        alerts.put("replayed", 1L);
        Map<String, Map<String, Long>> byRoute = new LinkedHashMap<>();
        Map<String, Long> spend = new LinkedHashMap<>();
        spend.put("sent", 3L); spend.put("failed", 1L); spend.put("dead_lettered", 0L);
        byRoute.put("spend_alert", spend);
        alerts.put("by_route", byRoute);
        snap.put("alerts", alerts);

        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_suppressed 4"));
        assertTrue(out.contains("imini_alerts_replayed 1"));
        assertTrue(out.contains("imini_alerts_route_sent{route=\"spend_alert\"} 3"));
        assertTrue(out.contains("imini_alerts_route_failed{route=\"spend_alert\"} 1"));
        assertTrue(out.contains("# TYPE imini_alerts_route_sent counter"));
    }
}
