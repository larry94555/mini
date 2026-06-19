package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Escalation-ladder parsing + duration parsing, and dedup digest payload. */
class AlertLadderDigestTest {

    @Test
    void parseDurationUnits() {
        assertEquals(30_000L, AlertSink.parseDuration("30s"));
        assertEquals(15L * 60_000L, AlertSink.parseDuration("15m"));
        assertEquals(2L * 3_600_000L, AlertSink.parseDuration("2h"));
        assertEquals(86_400_000L, AlertSink.parseDuration("1d"));
        assertEquals(500L, AlertSink.parseDuration("500")); // bare ms
        assertEquals(-1L, AlertSink.parseDuration("abc"));
        assertEquals(-1L, AlertSink.parseDuration(""));
    }

    @Test
    void parseTiersSortsAndKeepsTemplate() {
        List<AlertSink.Tier> t = AlertSink.parseTiers(
                "60m|https://sec/hi;;15m|https://sec/lo|{\"text\":\"{action}\"}");
        assertEquals(2, t.size());
        // sorted ascending by delay
        assertEquals(15L * 60_000L, t.get(0).afterMs());
        assertEquals("https://sec/lo", t.get(0).url());
        assertEquals("{\"text\":\"{action}\"}", t.get(0).template());
        assertEquals(60L * 60_000L, t.get(1).afterMs());
        assertEquals(null, t.get(1).template());
    }

    @Test
    void parseTiersSkipsMalformed() {
        List<AlertSink.Tier> t = AlertSink.parseTiers("badnourl;;10m|;;5m|https://ok");
        assertEquals(1, t.size());
        assertEquals("https://ok", t.get(0).url());
    }

    @Test
    void digestPayloadSummarizesKey() {
        String p = AlertSink.digestPayload("capability_denied|tool:run_command", 7, 60);
        assertTrue(p.contains("\"digest\":true"));
        assertTrue(p.contains("\"action\":\"capability_denied\""));
        assertTrue(p.contains("\"target\":\"tool:run_command\""));
        assertTrue(p.contains("\"suppressed\":7"));
        assertTrue(p.contains("\"window_seconds\":60"));
    }

    @Test
    void digestAndEscalationOffByDefault() {
        AlertSink s = new AlertSink(null, null);
        assertTrue(s.tiers().isEmpty());
        assertEquals(0, s.escalateStale(System.currentTimeMillis()));
        assertEquals(0, s.dedupDigestSweep(System.currentTimeMillis()));
    }

    @Test
    void statsExposesDigested() {
        assertTrue(new AlertSink(null, null).stats().containsKey("digested"));
    }
}
