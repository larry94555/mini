package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Additional deterministic retry-behavior coverage. */
class RetryContractTest {

    @Test
    void immediateSuccessDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = Retry.withBackoff(
                3,
                1,
                () -> {
                    calls.incrementAndGet();
                    return "ok";
                });

        assertEquals("ok", result);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesExhaustAndSurfaceLastIoException() {
        AtomicInteger calls = new AtomicInteger();

        IOException ex =
                assertThrows(
                        IOException.class,
                        () -> Retry.withBackoff(
                                3,
                                1,
                                () -> {
                                    calls.incrementAndGet();
                                    throw new IOException("still failing");
                                }));

        assertEquals(3, calls.get());
        assertTrue(ex.getMessage().contains("still failing"), ex.getMessage());
    }
}
