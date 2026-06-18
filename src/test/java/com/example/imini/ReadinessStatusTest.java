package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The /healthz status roll-up. */
class ReadinessStatusTest {
    @Test
    void rollsUpComponentStates() {
        assertEquals("ok", AgentController.readinessStatus(true, true));
        assertEquals("degraded", AgentController.readinessStatus(true, false));
        assertEquals("degraded", AgentController.readinessStatus(false, true));
        assertEquals("down", AgentController.readinessStatus(false, false));
    }
}
