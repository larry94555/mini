package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The /admin/slo window parser. */
class WindowParseTest {
    @Test
    void parsesUnits() {
        assertEquals(90_000L, AgentController.parseWindowMs("90s"));
        assertEquals(1_800_000L, AgentController.parseWindowMs("30m"));
        assertEquals(86_400_000L, AgentController.parseWindowMs("24h"));
        assertEquals(604_800_000L, AgentController.parseWindowMs("7d"));
        assertEquals(5000L, AgentController.parseWindowMs("5000")); // bare ms
    }

    @Test
    void allMeansSinceBeginning() {
        assertEquals(-1L, AgentController.parseWindowMs("all"));
        assertEquals(-1L, AgentController.parseWindowMs(""));
    }

    @Test
    void unknownFallsBackToOneDay() {
        assertEquals(86_400_000L, AgentController.parseWindowMs("xyz"));
        assertEquals(86_400_000L, AgentController.parseWindowMs(null));
    }
}
