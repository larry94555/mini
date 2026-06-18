package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void startsClosedAndAllowsCalls() {
        CircuitBreaker cb = new CircuitBreaker("t", 3, 60_000);
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowCall());
    }

    @Test
    void opensAfterThreshold() {
        CircuitBreaker cb = new CircuitBreaker("t", 3, 60_000);
        cb.recordFailure(); assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure(); assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        cb.recordFailure(); assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowCall());
    }

    @Test
    void successResetsToClosedAndClearsCount() {
        CircuitBreaker cb = new CircuitBreaker("t", 2, 60_000);
        cb.recordFailure(); cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowCall());
        // one failure after reset does not re-open (threshold is 2)
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void halfOpenAfterCooldown() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("t", 1, 50); // 50ms cooldown
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowCall());
        Thread.sleep(60);
        assertTrue(cb.allowCall()); // cooldown elapsed -> half-open probe allowed
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
    }

    @Test
    void callHelperThrowsOpenExceptionWhenOpen() {
        CircuitBreaker cb = new CircuitBreaker("t", 1, 60_000);
        cb.recordFailure(); // opens
        assertThrows(CircuitBreaker.OpenException.class, () -> cb.call(() -> "x"));
    }
}
