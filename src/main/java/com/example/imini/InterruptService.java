package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-session interruptibility and steering. Keyed by sessionId so concurrent runs don't affect each
 * other:
 *
 *   - interrupt(sessionId) sets a stop flag the engine checks between turns and mid-stream, so one
 *     run can be halted gracefully (partial result returned) without touching the others.
 *   - steer(sessionId, msg) queues guidance the engine injects as a user message at that session's
 *     next turn.
 *
 * A SECOND request (POST /interrupt or /steer, carrying the sessionId) reaches the running loop.
 */
@Component
public class InterruptService {

    private final Map<String, AtomicBoolean> stops = new ConcurrentHashMap<>();
    private final Map<String, Queue<String>> steers = new ConcurrentHashMap<>();

    public void interrupt(String sessionId) {
        stops.computeIfAbsent(sessionId, k -> new AtomicBoolean()).set(true);
    }

    /** True if a stop is pending for this session; also clears it. */
    public boolean consumeStop(String sessionId) {
        AtomicBoolean b = stops.get(sessionId);
        return b != null && b.getAndSet(false);
    }

    /** Non-clearing check, used to abort an in-progress stream. */
    public boolean isStopRequested(String sessionId) {
        AtomicBoolean b = stops.get(sessionId);
        return b != null && b.get();
    }

    public void steer(String sessionId, String message) {
        if (message != null && !message.isBlank()) {
            steers.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).add(message);
        }
    }

    public List<String> drainSteer(String sessionId) {
        Queue<String> q = steers.get(sessionId);
        List<String> out = new ArrayList<>();
        if (q != null) {
            String s;
            while ((s = q.poll()) != null) out.add(s);
        }
        return out;
    }
}
