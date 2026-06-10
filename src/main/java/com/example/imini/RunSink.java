package com.example.imini;

/**
 * Where a run's output goes. The blocking endpoints use a ConsoleSink (stdout); the streaming
 * endpoints use an SSE-backed sink. Routing output through this interface instead of System.out is
 * what lets several concurrent runs each stream to their own client.
 */
public interface RunSink {

    void token(String text);   // a streamed model token
    void log(String line);     // a structured run log line (tool call, guard, plan, steer, ...)

    /**
     * A named structured event (e.g. "approval"). SSE sinks emit it as a distinct event type;
     * other sinks fall back to logging it.
     */
    default void event(String type, String data) {
        log("[" + type + "] " + data);
    }

    /** Discards everything; used when no output target is attached. */
    RunSink NOOP = new RunSink() {
        @Override public void token(String text) {}
        @Override public void log(String line) {}
    };
}
