package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure whole-workspace bundle summary. */
class WorkspaceBundleTest {

    @Test
    void summarizeCountsAndTotal() {
        Map<String, Object> s = WorkspaceBundle.summarize(3, 1, 2, 5);
        assertEquals(WorkspaceBundle.FORMAT, s.get("format"));
        assertEquals(3, s.get("skills"));
        assertEquals(1, s.get("agents"));
        assertEquals(2, s.get("commands"));
        assertEquals(5, s.get("settings"));
        assertEquals(6, s.get("entries")); // skills + agents + commands
    }

    @Test
    void summarizeClampsNegatives() {
        Map<String, Object> s = WorkspaceBundle.summarize(-1, -2, -3, -4);
        assertEquals(0, s.get("skills"));
        assertEquals(0, s.get("entries"));
        assertEquals(0, s.get("settings"));
    }
}
