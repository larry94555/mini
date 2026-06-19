package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertSinkBufferTest {

    @Test
    void backoffIsExponentialAndCapped() {
        long base = 500;
        assertEquals(500L, AlertSink.backoffMs(1, base));   // 500 * 2^0
        assertEquals(1000L, AlertSink.backoffMs(2, base));  // 500 * 2^1
        assertEquals(2000L, AlertSink.backoffMs(3, base));  // 500 * 2^2
        assertEquals(4000L, AlertSink.backoffMs(4, base));  // 500 * 2^3
        assertTrue(AlertSink.backoffMs(100, base) <= 60_000L); // capped, no overflow
    }

    @Test
    void backoffHandlesZeroBaseAndLowAttempt() {
        assertTrue(AlertSink.backoffMs(0, 500) >= 0);
        assertTrue(AlertSink.backoffMs(1, 0) >= 0); // base<=0 guarded
    }

    @Test
    void statsExposesDeliveryCounters() {
        AlertSink s = new AlertSink(null, null);
        var stats = s.stats();
        assertTrue(stats.containsKey("sent"));
        assertTrue(stats.containsKey("failed"));
        assertTrue(stats.containsKey("retried"));
        assertTrue(stats.containsKey("dead_lettered"));
        assertTrue(stats.containsKey("dropped"));
        assertTrue(stats.containsKey("dead_letter_size"));
        assertEquals(0L, stats.get("sent"));
    }

    @Test
    void deadLettersEmptyInitially() {
        assertTrue(new AlertSink(null, null).deadLetters().isEmpty());
    }
}
