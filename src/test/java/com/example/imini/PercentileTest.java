package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The nearest-rank percentile used for p50/p95 latency SLOs. */
class PercentileTest {

    @Test
    void nearestRankPercentiles() {
        long[] s = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100}; // sorted, n=10
        assertEquals(50, Metrics.percentile(s, 0.50));  // ceil(0.5*10)-1 = idx 4 -> 50
        assertEquals(100, Metrics.percentile(s, 0.95)); // ceil(0.95*10)-1 = idx 9 -> 100
        assertEquals(10, Metrics.percentile(s, 0.0));
        assertEquals(100, Metrics.percentile(s, 1.0));
    }

    @Test
    void emptyAndSingle() {
        assertEquals(0, Metrics.percentile(new long[0], 0.95));
        assertEquals(0, Metrics.percentile(null, 0.5));
        assertEquals(42, Metrics.percentile(new long[]{42}, 0.95));
    }
}
