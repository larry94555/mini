package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromFormatTierTest {

    @Test
    void rendersPerTierEscalationCounters() {
        Map<String, Object> snap = new LinkedHashMap<>();
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("escalated", 5L);
        Map<String, Long> byTier = new LinkedHashMap<>();
        byTier.put("1", 3L);
        byTier.put("2", 2L);
        alerts.put("by_tier", byTier);
        snap.put("alerts", alerts);

        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_escalated_tier{tier=\"1\"} 3"));
        assertTrue(out.contains("imini_alerts_escalated_tier{tier=\"2\"} 2"));
        assertTrue(out.contains("# TYPE imini_alerts_escalated_tier counter"));
    }
}
