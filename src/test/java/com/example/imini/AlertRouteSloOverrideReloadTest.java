package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-route SLO objective overrides, error-budget-remaining, and config hot-reload. */
class AlertRouteSloOverrideReloadTest {

    // ---- Feature 1: per-route objective parsing + resolution ----

    @Test
    void parseRoutesReadsPerRouteObjective() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes(
                "spend_alert|https://a|{\"t\":1}|500|0.999;;capability_denied|https://b");
        AlertSink.Route sa = r.get("spend_alert");
        assertEquals("https://a", sa.url());
        assertEquals("{\"t\":1}", sa.template());
        assertEquals(500L, sa.latencyMs());
        assertEquals(0.999, sa.target());
        AlertSink.Route cd = r.get("capability_denied");
        assertEquals(0L, cd.latencyMs());   // inherit
        assertEquals(0.0, cd.target());     // inherit
    }

    @Test
    void parseRoutesBareLatencyNoTemplate() {
        // action|url||latency  (empty template)
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes("x|https://x||250");
        assertEquals(null, r.get("x").template());
        assertEquals(250L, r.get("x").latencyMs());
    }

    @Test
    void parseRoutesIgnoresOutOfRangeTarget() {
        Map<String, AlertSink.Route> r = AlertSink.parseRoutes("x|https://x|tmpl|100|1.5"); // target >=1 invalid
        assertEquals(0.0, r.get("x").target());
    }

    // ---- Feature 2: error-budget-remaining ----

    @Test
    void budgetRemainingUnderBudget() {
        Map<String, Object> s = AlertSink.sloSnapshot(995, 1000, 0.99, 1000); // 0.5% err vs 1% budget
        assertEquals(0.5, (double) s.get("budget_used"));        // half the budget used
        assertEquals(0.5, (double) s.get("budget_remaining"));
    }

    @Test
    void budgetRemainingExhaustedGoesNegative() {
        Map<String, Object> s = AlertSink.sloSnapshot(90, 100, 0.99, 1000); // 10% err vs 1% budget
        assertEquals(10.0, (double) s.get("budget_used"));
        assertEquals(-9.0, (double) s.get("budget_remaining"));  // overspent
    }

    @Test
    void promExportsBudgetRemaining() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        Map<String, Object> alerts = new java.util.LinkedHashMap<>();
        alerts.put("delivery_slo", AlertSink.sloSnapshot(995, 1000, 0.99, 1000));
        Map<String, Map<String, Object>> sbr = new java.util.LinkedHashMap<>();
        sbr.put("spend_alert", AlertSink.sloSnapshot(8, 10, 0.99, 1000));
        alerts.put("slo_by_route", sbr);
        snap.put("alerts", alerts);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_slo_budget_remaining 0.5"));
        assertTrue(out.contains("imini_alerts_route_slo_budget_remaining{route=\"spend_alert\"}"));
    }

    // ---- Feature 3: hot-reload ----

    @Test
    void reloadReparsesRoutesAndActions() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> snap = s.reload("a,b,c", "a|https://a|tmpl|750|0.995", null, 2000L, 0.95);
        // routes reflected
        assertTrue(snap.get("routes") instanceof List);
        List<?> routes = (List<?>) snap.get("routes");
        assertEquals(1, routes.size());
        // per-route resolver now sees the override
        assertEquals(750L, s.sloLatencyMsFor("a"));
        assertEquals(0.995, s.sloTargetFor("a"));
        // global slo updated
        assertEquals(2000L, s.sloLatencyMsFor("unmapped")); // inherits new global
        assertEquals(0.95, s.sloTargetFor("unmapped"));
    }

    @Test
    void reloadNullArgsLeaveUnchanged() {
        AlertSink s = new AlertSink(null, null);
        s.reload("x,y", "x|https://x", null, 1500L, 0.97);
        s.reload(null, null, null, null, null); // no-op
        assertEquals(1500L, s.sloLatencyMsFor("unmapped"));
        assertEquals(0.97, s.sloTargetFor("unmapped"));
    }
}
