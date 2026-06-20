package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configurable digest template, since-last-digest deltas, and worst-route-by-delivery-success. */
class AlertDigestTemplateDeltaTest {

    private static Map<String, Object> baseDigest() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("window_days", 30);
        d.put("window_success_ratio", 0.985);
        d.put("window_budget_remaining", 0.5);
        d.put("slo_target", 0.99);
        d.put("delivery_success_ratio", 0.997);
        d.put("success_target", 0.99);
        d.put("worst_route", "spend_alert");
        d.put("worst_route_ratio", 0.92);
        d.put("worst_success_route", "page_oncall");
        d.put("worst_success_route_ratio", 0.8);
        return d;
    }

    // ---- Feature 1: configurable template ----

    @Test
    void renderDigestUsesTemplateWhenSet() {
        String tmpl = "SLO {window_ratio} budget {window_budget} worst {worst_route} delivery {worst_success_route}";
        String out = AlertSink.renderDigest(baseDigest(), tmpl);
        assertEquals("SLO 98.5% budget 50% worst spend_alert delivery page_oncall", out);
    }

    @Test
    void renderDigestFallsBackToDefaultWhenBlank() {
        String out = AlertSink.renderDigest(baseDigest(), "");
        assertTrue(out.startsWith("imini SLO digest:"));
        assertTrue(out.contains("window 98.5%"));
    }

    @Test
    void templatePlaceholdersForDeltas() {
        Map<String, Object> d = baseDigest();
        d.put("budget_delta", -0.012);
        d.put("since_last_minutes", 60L);
        String out = AlertSink.renderDigest(d, "burned {budget_delta} in {since_last_minutes}m");
        assertEquals("burned -1.2pp in 60m", out);
    }

    @Test
    void templateDeltaPlaceholderNaWhenMissing() {
        String out = AlertSink.renderDigest(baseDigest(), "delta={budget_delta}");
        assertEquals("delta=n/a", out);
    }

    // ---- Feature 3: worst route by delivery-success ----

    @Test
    void defaultFormatIncludesBothWorstRoutes() {
        String out = AlertSink.formatSloDigest(baseDigest());
        assertTrue(out.contains("worst latency spend_alert @ 92%"));
        assertTrue(out.contains("worst delivery page_oncall @ 80%"));
    }

    @Test
    void digestHasSuccessWorstRouteFields() {
        Map<String, Object> d = new AlertSink(null, null).sloDigest();
        assertTrue(d.containsKey("worst_success_route"));
        assertTrue(d.containsKey("worst_success_route_ratio"));
    }

    // ---- Feature 2: deltas ----

    @Test
    void firstDigestHasNoDeltas() {
        // a fresh sink has no baseline; the first digest omits delta fields
        Map<String, Object> d = new AlertSink(null, null).sloDigest();
        assertFalse(d.containsKey("budget_delta"));
        assertFalse(d.containsKey("since_last_minutes"));
    }

    @Test
    void postDigestNoOpWithoutUrlStillReturnsSummary() {
        Map<String, Object> r = new AlertSink(null, null).postSloDigest();
        assertEquals(false, r.get("posted"));
        assertTrue(String.valueOf(r.get("summary")).startsWith("imini SLO digest:"));
    }

    @Test
    void secondDigestHasDeltasAfterPost() {
        AlertSink s = new AlertSink(null, null);
        s.postSloDigest();           // no URL, but advances the baseline
        Map<String, Object> d = s.sloDigest();
        assertTrue(d.containsKey("budget_delta"));
        assertTrue(d.containsKey("since_last_minutes"));
        assertTrue(d.containsKey("dead_lettered_delta"));
    }
}
