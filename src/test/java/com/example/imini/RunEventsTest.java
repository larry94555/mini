package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-run context-timeline EVENTS (not just counts) are captured on the run record and then reset. */
class RunEventsTest {

    @SuppressWarnings("unchecked")
    @Test
    void capturesEventLinesPerRunAndResets() {
        Metrics m = new Metrics(null, null);

        m.noteFold("[fold] condensed 90000 -> 132 chars");
        m.noteCompact("[compact] folded 6 older messages (~6200 tokens) into memory, kept 4 recent");
        m.recordRun("/chat", "s1", "auto", 20, true);

        Map<String, Object> r1 = m.recentRuns(1).get(0);
        List<String> ev1 = (List<String>) r1.get("events");
        assertEquals(2, ev1.size());
        assertTrue(ev1.get(0).startsWith("[fold]"));
        assertTrue(ev1.get(1).startsWith("[compact]"));
        assertEquals(1, r1.get("folds"));
        assertEquals(1, r1.get("compactions"));

        // next run on the same thread starts with no events
        m.noteTrim("[trim] ~9000 tok > cap 7000; trimmed 1, dropped 2 -> ~6800 tok");
        m.recordRun("/chat", "s2", "auto", 7, true);
        Map<String, Object> r2 = m.recentRuns(1).get(0);
        List<String> ev2 = (List<String>) r2.get("events");
        assertEquals(1, ev2.size());
        assertTrue(ev2.get(0).startsWith("[trim]"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void noEventsWhenNoContextActivity() {
        Metrics m = new Metrics(null, null);
        m.recordRun("/ask", "s", "auto", 4, true);
        List<String> ev = (List<String>) m.recentRuns(1).get(0).get("events");
        assertTrue(ev.isEmpty());
    }
}
