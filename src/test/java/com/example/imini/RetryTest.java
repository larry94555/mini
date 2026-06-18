package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryTest {
    @Test
    void backoffIsExponentialWithJitter() {
        assertEquals(400, Retry.delayMs(400, 0, 0.0));
        assertEquals(800, Retry.delayMs(400, 1, 0.0));
        assertEquals(1600, Retry.delayMs(400, 2, 0.0));
        assertEquals(600, Retry.delayMs(400, 0, 0.5)); // 400 + 50% jitter
        // capped at 30s
        assertTrue(Retry.delayMs(400, 30, 0.99) <= 30_000);
        // jitter never reduces below the base exponential
        assertTrue(Retry.delayMs(400, 1, 0.9) >= 800);
    }

    @Test
    void retriesIOExceptionUpToAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // baseMs=0 -> no real sleeping; fail first 2, succeed on 3rd
        String r = Retry.withBackoff(3, 0, () -> {
            if (calls.incrementAndGet() < 3) throw new IOException("transient");
            return "ok";
        });
        assertEquals("ok", r);
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryNonIOExceptions() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
                Retry.withBackoff(5, 0, () -> {
                    calls.incrementAndGet();
                    throw new IllegalArgumentException("client error");
                }));
        assertEquals(1, calls.get()); // tried once, propagated immediately
    }

    @Test
    void exhaustsThenThrowsLast() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () ->
                Retry.withBackoff(2, 0, () -> {
                    calls.incrementAndGet();
                    throw new IOException("always");
                }));
        assertEquals(2, calls.get());
    }
}
