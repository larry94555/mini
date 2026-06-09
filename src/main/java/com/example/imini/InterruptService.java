package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Interruptibility and steering. A run is normally driven by one HTTP request thread; this service
 * lets a SECOND request (POST /interrupt or /steer) reach into the running loop:
 *
 *   - interrupt() sets a stop flag the engine checks between turns and mid-stream, so a runaway or
 *     wrong-headed run can be halted gracefully (partial result returned).
 *   - steer(msg) queues guidance that the engine injects as a user message at the next turn, so you
 *     can correct the agent without restarting.
 *
 * It's a single global flag/queue -- fine for this single-user learning kit; a real system would key
 * it per session.
 */
@Component
public class InterruptService {

    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<String> steerQueue = new ConcurrentLinkedQueue<>();

    public void interrupt() {
        stop.set(true);
    }

    /** True if a stop is pending; also clears it. */
    public boolean consumeStop() {
        return stop.getAndSet(false);
    }

    /** Non-clearing check, used to abort an in-progress stream. */
    public boolean isStopRequested() {
        return stop.get();
    }

    public void steer(String message) {
        if (message != null && !message.isBlank()) steerQueue.add(message);
    }

    public List<String> drainSteer() {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = steerQueue.poll()) != null) out.add(s);
        return out;
    }
}
