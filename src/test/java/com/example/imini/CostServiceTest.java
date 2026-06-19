package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostServiceTest {

    @Test
    void microUsdComputesFromPerMillionPrices() {
        // 1,000,000 input tokens at $3/M + 0 output = $3.00 = 3,000,000 micro-USD
        assertEquals(3_000_000L, CostService.microUsd(1_000_000, 0, 3.0, 0.0));
        // 500,000 output tokens at $15/M = $7.50 = 7,500,000 micro-USD
        assertEquals(7_500_000L, CostService.microUsd(0, 500_000, 0.0, 15.0));
        // combined
        assertEquals(3_000_000L + 7_500_000L,
                CostService.microUsd(1_000_000, 500_000, 3.0, 15.0));
        // free/local model
        assertEquals(0L, CostService.microUsd(123_456, 789_012, 0.0, 0.0));
    }

    @Test
    void microUsdRoundsToNearestMicro() {
        // 1 token at $1/M = $0.000001 = 1 micro-USD
        assertEquals(1L, CostService.microUsd(1, 0, 1.0, 0.0));
        // 1 token at $0.4/M = 0.4 micro-USD -> rounds to 0
        assertEquals(0L, CostService.microUsd(1, 0, 0.4, 0.0));
    }

    @Test
    void startOfMonthIsStable() {
        // two instants in the same UTC month share a month start; different months differ
        long jan15 = java.time.LocalDate.of(2026, 1, 15)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        long jan28 = java.time.LocalDate.of(2026, 1, 28)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        long feb02 = java.time.LocalDate.of(2026, 2, 2)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(CostService.startOfMonthMs(jan15), CostService.startOfMonthMs(jan28));
        assertTrue(CostService.startOfMonthMs(feb02) > CostService.startOfMonthMs(jan28));
        // the month start is itself the 1st at 00:00 UTC
        long jan1 = java.time.LocalDate.of(2026, 1, 1)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(jan1, CostService.startOfMonthMs(jan15));
    }
}
