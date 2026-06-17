package com.example.imini;

import java.util.Locale;

/**
 * Pure predicate for filtering run-history records in the admin view: by endpoint substring, outcome
 * (ok/failed), and session substring. Blank/any filters match everything. Dependency-free and
 * deterministic so the matching rules are easy to unit-test; {@link RunHistory} applies it.
 */
public final class RunFilter {

    private RunFilter() {}

    /**
     * @param endpoint substring match on the endpoint (case-insensitive); blank = any
     * @param outcome  "ok" | "failed" (anything else, incl. blank, = any)
     * @param session  substring match on the session id (case-insensitive); blank = any
     */
    public static boolean matches(RunHistory.Record r, String endpoint, String outcome, String session) {
        if (r == null) return false;
        if (!containsCi(r.endpoint(), endpoint)) return false;
        if (!sessionOk(r.session(), session)) return false;
        return outcomeOk(r.ok(), outcome);
    }

    /** Exact (case-insensitive) session match, null-safe. Used by the per-session run view. */
    public static boolean sessionEquals(String value, String sessionId) {
        if (sessionId == null) return false;
        return sessionId.equalsIgnoreCase(value == null ? "" : value.trim());
    }

    public static boolean outcomeOk(boolean ok, String outcome) {
        if (outcome == null) return true;
        String o = outcome.trim().toLowerCase(Locale.ROOT);
        if (o.equals("ok") || o.equals("success")) return ok;
        if (o.equals("failed") || o.equals("fail") || o.equals("error")) return !ok;
        return true; // "", "any", anything else -> no outcome filter
    }

    private static boolean sessionOk(String value, String filter) {
        return containsCi(value, filter);
    }

    private static boolean containsCi(String value, String filter) {
        if (filter == null || filter.isBlank()) return true;
        if (value == null) return false;
        return value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }
}
