package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SLA field parsing in tiers, breach counter in stats, and the overview dashboard render. */
class AlertSlaBreachOverviewTest {

    @Test
    void parseTiersReadsOptionalSla() {
        // delay|url|template|sla  and  delay|url|sla (no template)
        List<AlertSink.Tier> t = AlertSink.parseTiers(
                "15m|https://a|{\"x\":1}|30m;;1h|https://b|45m");
        assertEquals(2, t.size());
        assertEquals(15L * 60_000L, t.get(0).afterMs());
        assertEquals("{\"x\":1}", t.get(0).template());
        assertEquals(30L * 60_000L, t.get(0).slaMs());
        assertEquals("https://b", t.get(1).url());
        assertEquals(null, t.get(1).template());     // bare sla, no template
        assertEquals(45L * 60_000L, t.get(1).slaMs());
    }

    @Test
    void parseTiersNoSlaDefaultsZero() {
        List<AlertSink.Tier> t = AlertSink.parseTiers("15m|https://a|{\"x\":1}");
        assertEquals(1, t.size());
        assertEquals(0L, t.get(0).slaMs());
        assertEquals("{\"x\":1}", t.get(0).template());
    }

    @Test
    void statsExposesSlaBreaches() {
        assertTrue(new AlertSink(null, null).stats().containsKey("sla_breaches"));
    }

    @Test
    void overviewRendersCountersRoutesTiers() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("queued", 10L); stats.put("sent", 7L); stats.put("failed", 2L);
        stats.put("dead_lettered", 1L); stats.put("escalated", 3L); stats.put("sla_breaches", 1L);
        stats.put("dead_letter_size", 4L);
        Map<String, Map<String, Long>> br = new LinkedHashMap<>();
        Map<String, Long> r = new LinkedHashMap<>();
        r.put("sent", 7L); r.put("failed", 2L); r.put("dead_lettered", 1L); r.put("suppressed", 0L);
        br.put("spend_alert", r);
        stats.put("by_route", br);
        Map<String, Long> bt = new LinkedHashMap<>(); bt.put("1", 2L); bt.put("2", 1L);
        stats.put("by_tier", bt);
        Map<String, Map<String, Long>> sla = new LinkedHashMap<>();
        Map<String, Long> s1 = new LinkedHashMap<>(); s1.put("count", 2L); s1.put("avg_ms", 90_000L); s1.put("max_ms", 120_000L);
        sla.put("1", s1);
        stats.put("ack_sla_by_tier", sla);

        var digests = List.of(new AlertSink.DedupSummary("capability_denied|t", "capability_denied", "t", 9, 0L));
        String html = AlertsOverview.render(stats, digests);
        assertTrue(html.contains("imini alerting overview"));
        assertTrue(html.contains("sla breaches"));
        assertTrue(html.contains("By route"));
        assertTrue(html.contains("spend_alert"));
        assertTrue(html.contains("Escalation tiers"));
        assertTrue(html.contains("T1"));
        assertTrue(html.contains("Top suppressed keys"));
        assertTrue(html.contains("capability_denied"));
    }

    @Test
    void overviewHandlesEmptyStats() {
        String html = AlertsOverview.render(null, List.of());
        assertTrue(html.contains("imini alerting overview"));
        assertTrue(!html.contains("By route"));
    }

    @Test
    void humanMsFormats() {
        assertEquals("\u2014", AlertsOverview.humanMs(0));
        assertEquals("500ms", AlertsOverview.humanMs(500));
        assertEquals("3s", AlertsOverview.humanMs(3000));
        assertEquals("2m", AlertsOverview.humanMs(120_000));
        assertEquals("2h", AlertsOverview.humanMs(7_200_000));
    }
}
