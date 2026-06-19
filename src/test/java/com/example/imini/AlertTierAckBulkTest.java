package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Escalation tier/ack surfaced in record + dashboard, per-tier stats, and bulk ack/replay gating. */
class AlertTierAckBulkTest {

    private static AlertSink.DeadLetter dl(int tier, long ackedAt) {
        return new AlertSink.DeadLetter("id1", System.currentTimeMillis(), "{\"a\":1}", "https://u",
                3, "HTTP 500", "failed", 0L, "spend_alert", tier, 0L, ackedAt);
    }

    @Test
    void deadLetterRecordCarriesTierAndAck() {
        AlertSink.DeadLetter d = dl(2, 123L);
        assertEquals(2, d.escalationTier());
        assertEquals(123L, d.ackedAt());
    }

    @Test
    void dashboardShowsTierAndAckBadgeAndBulkButtons() {
        String html = DeadLetterDashboard.render(List.of(dl(2, 999L)), "spend_alert", "failed", "", 0, 50, 1);
        assertTrue(html.contains("<th>tier</th>"));
        assertTrue(html.contains("T2"));                 // tier cell
        assertTrue(html.contains("badge\">acked"));       // ack badge
        assertTrue(html.contains("/admin/alerts/replay-all?action=spend_alert"));
        assertTrue(html.contains("/admin/alerts/ack-all?action=spend_alert"));
    }

    @Test
    void dashboardTierCellDashWhenNotEscalated() {
        assertEquals("\u2014", DeadLetterDashboard.tierCell(dl(0, 0L)));
        assertEquals("T3", DeadLetterDashboard.tierCell(dl(3, 0L)));
    }

    @Test
    void ackedRowHidesPerRowAckButton() {
        String acked = DeadLetterDashboard.render(List.of(dl(1, 555L)), "", "", "", 0, 50, 1);
        assertTrue(!acked.contains("/admin/alerts/ack?id=id1")); // already acked -> no per-row Ack
        String open = DeadLetterDashboard.render(List.of(dl(1, 0L)), "", "", "", 0, 50, 1);
        assertTrue(open.contains("/admin/alerts/ack?id=id1"));   // not acked -> Ack present
    }

    @Test
    void statsExposesByTier() {
        assertTrue(new AlertSink(null, null).stats().containsKey("by_tier"));
    }

    @Test
    void bulkAckReplayNoOpWithoutDb() {
        AlertSink s = new AlertSink(null, null);
        assertEquals(0, s.ackMatching(null, null, null));
        assertEquals(0, s.replayMatching(null, null, null));
    }
}
