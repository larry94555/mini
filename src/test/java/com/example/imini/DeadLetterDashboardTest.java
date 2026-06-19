package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadLetterDashboardTest {

    private static AlertSink.DeadLetter dl(String id, String action, String status, String payload) {
        return new AlertSink.DeadLetter(id, System.currentTimeMillis() - 5000, payload, "https://u",
                4, "HTTP 500", status, 0L, action, 0, 0L, 0L);
    }

    @Test
    void rendersTableFiltersAndActions() {
        var rows = List.of(dl("abc", "spend_alert", "failed", "{\"action\":\"spend_alert\"}"));
        String html = DeadLetterDashboard.render(rows, "spend_alert", "failed", "acct", 0, 50, 1);
        assertTrue(html.contains("<title>imini — dead-letter alerts</title>"));
        assertTrue(html.contains("Showing 1\u20131 of 1"));
        assertTrue(html.contains("spend_alert"));
        assertTrue(html.contains("/admin/alerts/replay?id=abc"));
        assertTrue(html.contains("/admin/alerts/ack?id=abc"));
        assertTrue(html.contains("/admin/alerts/failed?id=abc"));
        assertTrue(html.contains("value=\"spend_alert\"")); // filter round-trips
    }

    @Test
    void escapesPayload() {
        var rows = List.of(dl("x", "a", "failed", "<script>alert(1)</script>"));
        String html = DeadLetterDashboard.render(rows, "", "", "", 0, 50, 1);
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(!html.contains("<script>alert(1)"));
    }

    @Test
    void humanAgeFormats() {
        assertTrue(DeadLetterDashboard.humanAge(5_000).endsWith("s"));
        assertTrue(DeadLetterDashboard.humanAge(120_000).equals("2m"));
        assertTrue(DeadLetterDashboard.humanAge(7_200_000).equals("2h"));
        assertTrue(DeadLetterDashboard.humanAge(172_800_000L).equals("2d"));
    }

    @Test
    void pagerPreservesFilters() {
        String url = DeadLetterDashboard.link("spend_alert", "failed", "q1", 50, 50);
        assertTrue(url.contains("action=spend_alert"));
        assertTrue(url.contains("status=failed"));
        assertTrue(url.contains("offset=50"));
    }
}
