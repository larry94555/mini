package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpendAlertTest {

    @Test
    void thresholdTakesLowerOfAbsoluteAndPercent() {
        // quota 1000, 80% = 800; absolute 500 -> lower is 500
        assertEquals(500, CostService.alertThresholdTokens(1000, 500, 80));
        // quota 1000, 80% = 800; absolute 0 (off) -> 800
        assertEquals(800, CostService.alertThresholdTokens(1000, 0, 80));
        // percent off -> absolute
        assertEquals(500, CostService.alertThresholdTokens(1000, 500, 0));
        // both off -> 0 (alerts disabled)
        assertEquals(0, CostService.alertThresholdTokens(1000, 0, 0));
        // percent set but no quota -> percent contributes nothing -> absolute
        assertEquals(500, CostService.alertThresholdTokens(0, 500, 80));
    }

    @Test
    void crossedIsEdgeTriggered() {
        assertTrue(CostService.crossed(700, 850, 800));   // crossed up through 800
        assertFalse(CostService.crossed(850, 900, 800));  // already above -> no re-alert
        assertFalse(CostService.crossed(700, 750, 800));  // not yet reached
        assertFalse(CostService.crossed(700, 850, 0));    // threshold 0 = disabled
        assertTrue(CostService.crossed(0, 800, 800));     // exactly at threshold counts
    }
}
