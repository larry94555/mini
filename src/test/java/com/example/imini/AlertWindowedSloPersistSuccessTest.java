package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rolling-window SLO, persisted hot-reload serialization, and per-route delivery-success SLO. */
class AlertWindowedSloPersistSuccessTest {

    private static final long DAY = 86_400_000L;

    // ---- Feature 1: rolling window (pure, time-injected) ----

    @Test
    void windowSumsRecentDaysOnly() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(30);
        long t0 = 1_000L * DAY; // arbitrary epoch-day boundary
        // 10 records today: 8 good
        for (int i = 0; i < 8; i++) w.record(t0, true);
        for (int i = 0; i < 2; i++) w.record(t0, false);
        long[] s = w.snapshot(t0);
        assertEquals(8L, s[0]);
        assertEquals(10L, s[1]);
    }

    @Test
    void windowEvictsBeyondHorizon() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(30);
        long t0 = 1_000L * DAY;
        w.record(t0, true);              // day 1000
        w.record(t0, true);
        long[] now = w.snapshot(t0 + 40 * DAY); // 40 days later: day-1000 bucket is outside the 30-day window
        assertEquals(0L, now[0]);
        assertEquals(0L, now[1]);
    }

    @Test
    void windowReusesSlotAfterFullCycle() {
        AlertSink.RollingWindow w = new AlertSink.RollingWindow(7);
        long t0 = 500L * DAY;
        w.record(t0, true);                 // day 500 -> slot 500%7
        w.record(t0 + 7 * DAY, false);      // day 507 -> same slot, must reset (not accumulate)
        long[] s = w.snapshot(t0 + 7 * DAY);
        assertEquals(0L, s[0]);             // the new day has 0 good
        assertEquals(1L, s[1]);             // and only 1 total (old day evicted)
    }

    @Test
    void statsExposesWindowedSlo() {
        Map<String, Object> stats = new AlertSink(null, null).stats();
        assertTrue(stats.containsKey("delivery_slo_window"));
        assertTrue(((Map<?, ?>) stats.get("delivery_slo_window")).containsKey("window_days"));
    }

    // ---- Feature 2: persisted hot-reload serialization (pure round-trip) ----

    @Test
    void serializeOverridesRoundTripsSpecialChars() throws Exception {
        String routes = "spend_alert|https://h/x|{\"t\":1}|500|0.999;;cap|https://y";
        String body = AlertSink.serializeOverrides("a,b", routes, "15m|https://esc", 800, 0.995);
        Properties p = new Properties();
        p.load(new java.io.StringReader(body));
        assertEquals("a,b", p.getProperty("alerts.actions"));
        assertEquals(routes, p.getProperty("alerts.routes"));       // pipes/braces/quotes survive
        assertEquals("15m|https://esc", p.getProperty("alerts.escalate-tiers"));
        assertEquals("800", p.getProperty("alerts.slo-latency-ms"));
        assertEquals("0.995", p.getProperty("alerts.slo-target"));
    }

    @Test
    void serializeOverridesOmitsBlanks() {
        String body = AlertSink.serializeOverrides(null, "", null, 0, 0);
        assertTrue(!body.contains("alerts.actions"));
        assertTrue(!body.contains("alerts.routes"));
        assertTrue(!body.contains("alerts.slo-latency-ms"));
    }

    // ---- Feature 3: delivery-success SLO ----

    @Test
    void deliverySuccessSloEmptyIsHealthy() {
        Map<String, Object> s = new AlertSink(null, null).deliverySuccessSlo();
        assertEquals(1.0, (double) s.get("success_ratio"));
        assertEquals(0.0, (double) s.get("burn_rate"));
    }

    @Test
    void successByRouteEmptyWithoutTraffic() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.successSloByRoute().isEmpty());
        assertTrue(s.stats().containsKey("success_by_route"));
    }

    @Test
    void promExportsWindowedSuccessAndPerRoute() {
        Map<String, Object> snap = new java.util.LinkedHashMap<>();
        Map<String, Object> alerts = new java.util.LinkedHashMap<>();
        Map<String, Object> win = AlertSink.sloSnapshot(280, 300, 0.99, 1000);
        win.put("window_days", 30);
        alerts.put("delivery_slo_window", win);
        alerts.put("delivery_success_slo", AlertSink.sloSnapshot(98, 100, 0.99, 0));
        Map<String, Map<String, Object>> sbr = new java.util.LinkedHashMap<>();
        sbr.put("spend_alert", AlertSink.sloSnapshot(9, 10, 0.99, 0));
        alerts.put("success_by_route", sbr);
        snap.put("alerts", alerts);
        String out = PromFormat.render(snap);
        assertTrue(out.contains("imini_alerts_slo_window_days 30"));
        assertTrue(out.contains("imini_alerts_slo_window_budget_remaining"));
        assertTrue(out.contains("imini_alerts_success_slo_ratio 0.98"));
        assertTrue(out.contains("imini_alerts_route_success_ratio{route=\"spend_alert\"} 0.9"));
        assertTrue(out.contains("imini_alerts_route_success_good_total{route=\"spend_alert\"} 9"));
    }
}
