package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Config-snapshot masking, CSRF config snapshot, and overview auto-refresh markup. */
class AlertConfigOverviewTest {

    @Test
    void maskUrlKeepsHostRedactsPath() {
        assertEquals("https://hooks.slack.com/***",
                AlertSink.maskUrl("https://hooks.slack.com/services/T00/B00/secrettoken"));
        assertEquals("https://example.com", AlertSink.maskUrl("https://example.com"));
        assertEquals("https://example.com", AlertSink.maskUrl("https://example.com/"));
        assertEquals("", AlertSink.maskUrl(""));
        assertEquals("", AlertSink.maskUrl(null));
    }

    @Test
    void configSnapshotHasResolvedKeysAndNoRawSecrets() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> c = s.configSnapshot();
        // structural keys present
        for (String k : List.of("enabled", "webhook_url", "actions", "routes", "escalation_enabled",
                "escalation_tiers", "dedup_window_seconds", "retention_hours", "dead_letter_persistent_effective")) {
            assertTrue(c.containsKey(k), "missing " + k);
        }
        assertTrue(c.get("actions") instanceof List);
        assertTrue(c.get("escalation_tiers") instanceof List);
    }

    @Test
    void csrfConfigSnapshotHidesSecret() {
        Map<String, Object> m = new CsrfGuard().configSnapshot();
        assertTrue(m.containsKey("enabled"));
        assertEquals("per-process", m.get("secret_mode")); // no configured secret -> per-process
        assertTrue(m.containsKey("ttl_seconds"));
        assertTrue(!m.containsKey("secret"));
        assertTrue(!m.toString().toLowerCase().contains("secret-value"));
    }

    @Test
    void overviewStaticHasNoRefreshScript() {
        String html = AlertsOverview.render(Map.of("sent", 1L), List.of()); // 2-arg -> refresh 0
        assertTrue(!html.contains("setInterval"));
        assertTrue(!html.contains("Auto-refresh"));
    }

    @Test
    void overviewLiveHasRefreshScriptAndIds() {
        String html = AlertsOverview.render(Map.of("sent", 1L, "failed", 2L), List.of(), 10);
        assertTrue(html.contains("Auto-refresh every 10s"));
        assertTrue(html.contains("setInterval(poll,REFRESH*1000)"));
        assertTrue(html.contains("/admin/alerts/overview.json"));
        assertTrue(html.contains("id=\"c_failed\""));      // card has stable id for live update
        assertTrue(html.contains("id=\"rt_body\""));        // route table body present even if empty
        assertTrue(html.contains("id=\"tier_body\""));
        assertTrue(html.contains("id=\"sup_body\""));
        assertTrue(html.contains("/admin/alerts/config"));  // nav link
    }
}
