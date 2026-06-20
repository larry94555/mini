package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Digest history serialize/parse + cap, mute logic, and the overview Recent-digests/mute rendering. */
class AlertDigestHistoryMuteCapTest {

    // ---- Feature 1: history serialize/parse ----

    @Test
    void historyRowRoundTripsWithPipesInSummary() {
        String summary = "imini SLO digest: window 98% (budget 50% left), worst latency a|b @ 90%";
        String s = AlertSink.serializeDigestHistory(1_700_000_000_000L, true, "probe", summary);
        Map<String, Object> r = AlertSink.parseDigestHistory(s);
        assertEquals(1_700_000_000_000L, r.get("ts"));
        assertEquals(true, r.get("posted"));
        assertEquals("probe", r.get("mode"));
        assertEquals(summary, r.get("summary")); // summary tail preserved despite the '|'
    }

    @Test
    void parseHistoryRejectsMalformed() {
        assertNull(AlertSink.parseDigestHistory(null));
        assertNull(AlertSink.parseDigestHistory(""));
        assertNull(AlertSink.parseDigestHistory("only|three|fields"));
        assertNull(AlertSink.parseDigestHistory("notalong|true|probe|x"));
    }

    @Test
    void historyKeysSortNewestFirstLexically() {
        String older = AlertSink.digestHistoryKey(1_000L);
        String newer = AlertSink.digestHistoryKey(2_000L);
        assertTrue(newer.compareTo(older) > 0); // zero-padded so lexical DESC == chronological newest-first
    }

    @Test
    void historyEmptyWithoutDatabase() {
        assertTrue(new AlertSink(null, null).sloDigestHistory(20).isEmpty());
    }

    // ---- Feature 3: history cap ----

    @Test
    void historyKeysToPrunePicksBeyondMax() {
        List<String> keys = new ArrayList<>(List.of("k5", "k4", "k3", "k2", "k1")); // newest-first
        assertEquals(List.of("k2", "k1"), AlertSink.historyKeysToPrune(keys, 3));
        assertEquals(List.of(), AlertSink.historyKeysToPrune(keys, 5));
        assertEquals(List.of(), AlertSink.historyKeysToPrune(keys, 10));
        assertEquals(List.of(), AlertSink.historyKeysToPrune(List.of(), 3));
    }

    // ---- Feature 2: mute ----

    @Test
    void digestMutedComparesAgainstNow() {
        assertTrue(AlertSink.digestMuted(1000L, 2000L));   // muted: until in the future
        assertFalse(AlertSink.digestMuted(2000L, 2000L));  // boundary: not muted
        assertFalse(AlertSink.digestMuted(3000L, 2000L));  // expired
        assertFalse(AlertSink.digestMuted(1000L, 0L));     // never muted
    }

    @Test
    void muteUnmuteTogglesState() {
        AlertSink s = new AlertSink(null, null);
        assertEquals(0L, s.digestMuteUntil());
        long until = s.muteDigest(4);
        assertTrue(until > System.currentTimeMillis());
        assertEquals(until, s.digestMuteUntil());
        s.unmuteDigest();
        assertEquals(0L, s.digestMuteUntil());
    }

    @Test
    void mutedPostIsSuppressed() {
        AlertSink s = new AlertSink(null, null);
        s.muteDigest(1);
        Map<String, Object> r = s.postSloDigest();      // respects mute
        assertEquals(false, r.get("posted"));
        assertEquals("muted", r.get("mode"));
        Map<String, Object> f = s.postSloDigest(true);  // force overrides mute (still no URL -> not posted)
        assertFalse("muted".equals(f.get("mode")));
    }

    // ---- overview rendering ----

    @Test
    void muteNoteRendersState() {
        assertEquals("Digest not muted.", AlertsOverview.muteNote(0L, 1000L));
        assertTrue(AlertsOverview.muteNote(1000L + 3_600_000L, 1000L).contains("muted for"));
    }

    @Test
    void overviewRendersRecentDigests() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("ts", 1_700_000_000_000L); row.put("time", "2023-11-14T22:13:20Z");
        row.put("posted", true); row.put("mode", "probe"); row.put("summary", "imini SLO digest: window 99%");
        stats.put("recent_digests", List.of(row));
        stats.put("digest_muted_until", 0L);
        String html = AlertsOverview.render(stats, List.of());
        assertTrue(html.contains("Recent SLO digests"));
        assertTrue(html.contains("2023-11-14T22:13:20Z"));
        assertTrue(html.contains("window 99%"));
    }
}
