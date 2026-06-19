package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromFormatAlertsTest {

    @Test
    void rendersAlertAndAuditCounters() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("audit_capability_denied", 5L);
        counters.put("audit_tool_rate_limited", 2L);
        snap.put("counters", counters);
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("sent", 10L);
        alerts.put("dead_lettered", 1L);
        alerts.put("dropped", 0L);
        alerts.put("in_flight", 3L);
        snap.put("alerts", alerts);

        String out = PromFormat.render(snap);
        // security events surface through the counters block
        assertTrue(out.contains("imini_counter{name=\"audit_capability_denied\"} 5"));
        assertTrue(out.contains("imini_counter{name=\"audit_tool_rate_limited\"} 2"));
        // alert delivery stats
        assertTrue(out.contains("imini_alerts_sent 10"));
        assertTrue(out.contains("imini_alerts_dead_lettered 1"));
        assertTrue(out.contains("imini_alerts_in_flight 3"));
        assertTrue(out.contains("# TYPE imini_alerts_sent counter"));
    }

    @Test
    void emptySnapshotSafe() {
        assertFalse(PromFormat.render(new LinkedHashMap<>()).contains("imini_alerts_sent"));
    }
}
