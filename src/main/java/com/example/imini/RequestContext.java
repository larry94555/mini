package com.example.imini;

/**
 * The {@link Principal} for the CURRENT HTTP request, on the request thread. AuthFilter sets it before
 * the controller runs and clears it afterwards, so controllers can attribute actions and introspect
 * identity (GET /me) without threading a parameter through every handler. This is request-scoped and
 * separate from {@link SessionContext}, which follows the agent run onto async/worker threads.
 */
public final class RequestContext {

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACEPARENT = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(Principal p) {
        if (p == null) CURRENT.remove(); else CURRENT.set(p);
    }

    public static void clear() {
        CURRENT.remove();
        TRACEPARENT.remove();
    }

    /** Record the inbound W3C traceparent header (set by AuthFilter) for cross-service trace propagation. */
    public static void setTraceparent(String tp) {
        if (tp == null || tp.isBlank()) TRACEPARENT.remove(); else TRACEPARENT.set(tp);
    }

    /** The inbound traceparent for this request, or null when the caller sent none. */
    public static String traceparent() {
        return TRACEPARENT.get();
    }

    /** The caller, or {@link Principal#ANON} when none was set (e.g. auth disabled / open path). */
    public static Principal current() {
        Principal p = CURRENT.get();
        return p == null ? Principal.ANON : p;
    }
}
