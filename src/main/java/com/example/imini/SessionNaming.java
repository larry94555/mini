package com.example.imini;

/**
 * Pure helpers for the session fork/rename UX: normalizing a user-supplied title and deriving a
 * fork's title/id. Kept dependency-free so the rules (trimming, length cap, fallback, "fork of ..."
 * derivation) are deterministic and unit-testable; the actual store writes live in {@link SessionStore}
 * and the controller.
 */
public final class SessionNaming {

    private SessionNaming() {}

    /** Max stored title length. */
    public static final int MAX_TITLE = 80;

    /**
     * Normalize a title: trim, collapse internal whitespace/newlines to single spaces, and cap length.
     * Returns "" for null/blank input (callers treat "" as "no title").
     */
    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        t = t.replaceAll(" {2,}", " ");
        if (t.length() > MAX_TITLE) t = t.substring(0, MAX_TITLE).trim();
        return t;
    }

    /**
     * The title to give a fork. Prefers the source's display name; falls back to its id. Avoids stacking
     * "fork of fork of ..." -- if the source is already "fork of X" the new one stays "fork of X".
     */
    public static String forkTitle(String sourceTitle, String sourceId) {
        String base = cleanTitle(sourceTitle);
        if (base.isEmpty()) base = sourceId == null ? "session" : sourceId;
        if (base.toLowerCase().startsWith("fork of ")) return cleanTitle(base);
        return cleanTitle("fork of " + base);
    }

    /** A display name for a session: its title if set, otherwise its id. */
    public static String displayName(String title, String id) {
        String t = cleanTitle(title);
        return t.isEmpty() ? (id == null ? "" : id) : t;
    }
}
