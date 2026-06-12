package com.example.imini;

/**
 * Where a run's output goes.
 *
 * <p>The blocking endpoints use a {@code ConsoleSink}. The streaming endpoints use an SSE-backed
 * sink. Routing output through this interface instead of {@code System.out} lets several
 * concurrent runs stream to their own clients.
 */
public interface RunSink {

  /** A streamed model token. */
  void token(String text);

  /** A structured run log line: tool call, guard, plan, steer, etc. */
  void log(String line);

  /**
   * A named structured event such as {@code approval}. SSE sinks emit it as a distinct event type;
   * other sinks fall back to logging it.
   */
  default void event(String type, String data) {
    log("[" + type + "] " + data);
  }

  /** Discards everything; used when no output target is attached. */
  RunSink NOOP =
      new RunSink() {
        @Override
        public void token(String text) {}

        @Override
        public void log(String line) {}
      };
}
