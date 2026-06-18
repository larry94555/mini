package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simple three-state circuit breaker to protect the llama-server connection. After
 * {@code failureThreshold} consecutive transient failures the breaker opens and calls fail fast
 * (throwing {@link OpenException}) instead of burning the full retry budget; this also lets
 * {@code /healthz} report {@code degraded} immediately. A {@code cooldownMs} after opening, the
 * breaker moves to half-open: the next call is allowed through as a probe. If it succeeds the
 * breaker closes; if it fails the cooldown resets.
 *
 * <p>The state machine is pure and tested via {@link #recordSuccess()} / {@link #recordFailure()}
 * / {@link #allowCall()}.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Thrown when a call is rejected because the breaker is open. */
    public static class OpenException extends java.io.IOException {
        public OpenException(String msg) { super(msg); }
    }

    private final int failureThreshold;
    private final long cooldownMs;
    private final String name;

    private final AtomicInteger consecutive = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    public CircuitBreaker(String name, int failureThreshold, long cooldownMs) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownMs = Math.max(0, cooldownMs);
    }

    public State state() { return state.get(); }

    /**
     * Returns {@code true} if the call should be allowed through. Transitions OPEN → HALF_OPEN
     * once the cooldown has elapsed.
     */
    public boolean allowCall() {
        State s = state.get();
        if (s == State.CLOSED) return true;
        if (s == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[circuit:" + name + "] half-open — probing");
                }
                return true; // let the probe through
            }
            return false;
        }
        return true; // HALF_OPEN: probe is allowed
    }

    /** Call when an attempt succeeds; resets the breaker to CLOSED. */
    public void recordSuccess() {
        State prev = state.getAndSet(State.CLOSED);
        consecutive.set(0);
        if (prev != State.CLOSED) {
            log.info("[circuit:" + name + "] closed — llama-server recovered");
        }
    }

    /**
     * Call when a transient failure occurs (IOException / 5xx). Opens the breaker once the
     * threshold is reached; resets the cooldown if already open.
     */
    public void recordFailure() {
        int n = consecutive.incrementAndGet();
        if (n >= failureThreshold) {
            State prev = state.getAndSet(State.OPEN);
            openedAt.set(System.currentTimeMillis());
            if (prev != State.OPEN) {
                log.warn("[circuit:" + name + "] OPEN after " + n
                        + " consecutive failures — calls will fail fast for " + cooldownMs + "ms");
            }
        }
    }

    /**
     * Wrap an operation: if the breaker is open throw {@link OpenException}; otherwise run the op,
     * recording success or failure, and return the result.
     */
    public <T> T call(Retry.Op<T> op) throws Exception {
        if (!allowCall()) {
            throw new OpenException("[circuit:" + name + "] open — llama-server unavailable; "
                    + "retrying after " + cooldownMs + "ms cooldown");
        }
        try {
            T result = op.call();
            recordSuccess();
            return result;
        } catch (java.io.IOException e) {
            recordFailure();
            throw e;
        }
    }
}
