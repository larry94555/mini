package com.example.imini;

/**
 * Carries the current run's sessionId and output sink down to tool executors WITHOUT changing the
 * Tool function signature. The engine sets it around each tool execution -- on whatever thread runs
 * the tool, including the parallel pool -- so todo_write can scope to the right session and
 * delegate_research can stream the sub-agent's tokens to the same client. Stored in a ThreadLocal;
 * the engine saves/restores the previous value so nested runs (sub-agents) don't clobber the parent.
 */
public final class SessionContext {

    public record Ctx(String sessionId, RunSink sink) {}

    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    private SessionContext() {}

    public static void set(Ctx ctx) {
        if (ctx == null) CURRENT.remove(); else CURRENT.set(ctx);
    }

    public static Ctx get() { return CURRENT.get(); }

    public static String sessionId() {
        Ctx c = CURRENT.get();
        return c == null ? "default" : c.sessionId();
    }

    public static RunSink sink() {
        Ctx c = CURRENT.get();
        return (c == null || c.sink() == null) ? RunSink.NOOP : c.sink();
    }
}
