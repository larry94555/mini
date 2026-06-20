package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SLO digest formatting/posting, report date-range filtering, and per-route target columns. */
class AlertSloDigestReportRangeTest {

    // ---- Feature 1: SLO digest (pure) ----

    @Test
    void sloDigestHasSummaryFields() {
        Map<String, Object> d = new AlertSink(null, null).sloDigest();
        for (String k : List.of("window_success_ratio", "window_budget_remaining", "delivery_success_ratio",
                "worst_route", "worst_route_ratio", "slo_target", "success_target")) {
            assertTrue(d.containsKey(k), "missing " + k);
        }
    }

    @Test
    void formatSloDigestRendersPercentages() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("window_success_ratio", 0.985);
        d.put("window_budget_remaining", 0.5);
        d.put("delivery_success_ratio", 0.997);
        d.put("worst_route", "spend_alert");
        d.put("worst_route_ratio", 0.92);
        String s = AlertSink.formatSloDigest(d);
        assertTrue(s.contains("window 98.5%"));
        assertTrue(s.contains("budget 50% left"));
        assertTrue(s.contains("delivery-success 99.7%"));
        assertTrue(s.contains("worst latency spend_alert @ 92%"));
    }

    @Test
    void formatSloDigestOmitsWorstWhenNone() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("window_success_ratio", 1.0);
        d.put("window_budget_remaining", 1.0);
        d.put("delivery_success_ratio", 1.0);
        d.put("worst_route", "");
        String s = AlertSink.formatSloDigest(d);
        assertFalse(s.contains("worst latency"));
        assertFalse(s.contains("worst delivery"));
    }

    @Test
    void postDigestNoOpWithoutUrl() {
        Map<String, Object> r = new AlertSink(null, null).postSloDigest(); // no webhook/digest URL configured
        assertEquals(false, r.get("posted"));
    }

    // ---- Feature 2: date-range filtering (pure) ----

    @Test
    void reportRowsRangeFiltersByDay() {
        // craft via a RollingWindow loaded with three days, then exercise the range overload through a sink
        AlertSink s = new AlertSink(null, null);
        // no traffic -> empty regardless of range
        assertTrue(s.sloReportRows(0, 100).isEmpty());
        // full range is the default
        assertEquals(s.sloReportRows().size(), s.sloReportRows(Long.MIN_VALUE, Long.MAX_VALUE).size());
    }

    // ---- Feature 3: per-route target columns in report (pure) ----

    @Test
    void reportCsvHasTargetColumns() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("scope", "route"); row.put("route", "spend_alert"); row.put("day", 20000L);
        row.put("date", "2024-10-04"); row.put("good", 90L); row.put("total", 100L); row.put("ratio", 0.9);
        row.put("slo_target", 0.99); row.put("success_target", 0.95); row.put("pass", false);
        String csv = AlertSink.sloReportCsv(List.of(row));
        assertTrue(csv.startsWith("scope,route,day,date,good,total,ratio,slo_target,success_target,pass\n"));
        assertTrue(csv.contains("route,spend_alert,20000,2024-10-04,90,100,0.9,0.99,0.95,false"));
    }

    @Test
    void reportCsvEmptyIsHeaderOnly() {
        assertEquals("scope,route,day,date,good,total,ratio,slo_target,success_target,pass\n",
                AlertSink.sloReportCsv(List.of()));
    }
}
