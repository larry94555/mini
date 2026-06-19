package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditDashboardPagingTest {

    private static AuditLog.Entry e(String action) {
        return new AuditLog.Entry("id", 1700000000000L, "2023-11-14T22:13:20Z", "alice", action, "t", "ok");
    }

    @Test
    void showsRangeAndTotalAndPager() {
        // offset 3, limit 3, total 10 -> "Showing 4-6 of 10"
        String html = AuditDashboard.render(List.of(e("login"), e("login"), e("login")),
                "", "", "", "", "", 3, 3, 10);
        assertTrue(html.contains("Showing 4\u20136 of 10"));
        assertTrue(html.contains("Prev"));
        assertTrue(html.contains("Next"));
        // prev enabled (offset>0), next enabled (6<10)
        assertFalse(html.contains("class=\"disabled\" href=\"/admin/audit.html?user=&action=&target=&since=&until=&offset=0"));
    }

    @Test
    void firstPageDisablesPrev() {
        String html = AuditDashboard.render(List.of(e("login")), "", "", "", "", "", 0, 50, 1);
        assertTrue(html.contains("Showing 1\u20131 of 1"));
        // both prev and next disabled (single page)
        assertTrue(html.contains("class=\"disabled\""));
    }

    @Test
    void linkPreservesFiltersAndEncodes() {
        String url = AuditDashboard.link("ali ce", "spend_alert", "t&t", "2023-01-01", "", 6, 3);
        assertTrue(url.contains("user=ali+ce") || url.contains("user=ali%20ce"));
        assertTrue(url.contains("action=spend_alert"));
        assertTrue(url.contains("target=t%26t")); // & encoded
        assertTrue(url.contains("offset=6"));
        assertTrue(url.contains("limit=3"));
    }

    @Test
    void timeRangeInputsRendered() {
        String html = AuditDashboard.render(List.of(), "", "", "", "2023-01-01", "2023-02-01", 0, 50, 0);
        assertTrue(html.contains("name=\"since\""));
        assertTrue(html.contains("name=\"until\""));
        assertTrue(html.contains("value=\"2023-01-01\""));
        assertTrue(html.contains("value=\"2023-02-01\""));
    }
}
