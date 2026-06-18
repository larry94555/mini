package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-run context attribution: folds/compactions/trims noted during a run are recorded on that run's
 * RunHistory record and reset for the next run on the same thread.
 */
class RunContextStatsTest {

    @Test
    void attributesContextEventsToTheRunRecordAndResets() {
        Metrics m = new Metrics(null, null); // RunService + RunHistoryStore may be null in unit tests

        // run 1: two folds, one compaction
        m.noteFold();
        m.noteFold();
        m.noteCompact();
        m.recordRun("/chat", "s1", "auto", 12, true);

        List<Map<String, Object>> runs = m.recentRuns(1);
        assertEquals(1, runs.size());
        Map<String, Object> r1 = runs.get(0);
        assertEquals(2, r1.get("folds"));
        assertEquals(1, r1.get("compactions"));
        assertEquals(0, r1.get("trims"));

        // run 2 on the same thread: tally must have reset; only one trim now
        m.noteTrim();
        m.recordRun("/chat", "s2", "auto", 8, true);
        Map<String, Object> r2 = m.recentRuns(1).get(0); // newest first
        assertEquals(0, r2.get("folds"));
        assertEquals(0, r2.get("compactions"));
        assertEquals(1, r2.get("trims"));

        // global counters accumulate across runs
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) m.snapshot().get("context");
        assertEquals(2L, ctx.get("folds"));
        assertEquals(1L, ctx.get("compactions"));
        assertEquals(1L, ctx.get("trims"));
    }

    @Test
    void recordWithoutContextEventsHasZeroCounts() {
        Metrics m = new Metrics(null, null);
        m.recordRun("/ask", "s", "auto", 5, true);
        Map<String, Object> r = m.recentRuns(1).get(0);
        assertEquals(0, r.get("folds"));
        assertEquals(0, r.get("compactions"));
        assertEquals(0, r.get("trims"));
    }
}
