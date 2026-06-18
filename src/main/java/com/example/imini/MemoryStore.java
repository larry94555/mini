package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Durable, cross-session memory: one persistent {@code [MEMORY]} note per owner, stored in the
 * {@code memory} table. Unlike a session's in-conversation memory (which lives in that session's message
 * list), this note carries durable facts across <em>different</em> sessions and survives restarts. A new
 * session is seeded from it, and after a run the session's current memory note is written back here, so
 * knowledge accumulates over time. Falls back to a no-op / empty when persistence is unavailable.
 */
@Component
public class MemoryStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemoryStore.class);

    static final String DEFAULT_OWNER = "local";
    private static volatile String WS_ID; // cached workspace id (hash of the working directory)

    private final Database db;

    public MemoryStore(Database db) {
        this.db = db;
    }

    /**
     * A stable, short id for the current workspace (derived from the working directory), so durable memory
     * is scoped per project rather than shared across every repo a single owner touches.
     */
    public static String workspaceId() {
        String v = WS_ID;
        if (v != null) return v;
        String path = java.nio.file.Path.of("").toAbsolutePath().normalize().toString();
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            v = sb.toString();
        } catch (Exception e) {
            v = Integer.toHexString(path.hashCode());
        }
        WS_ID = v;
        return v;
    }

    /** The storage key: owner scoped to the current workspace, e.g. {@code local@1a2b3c4d5e6f}. */
    private static String key(String owner) {
        String o = (owner == null || owner.isBlank()) ? DEFAULT_OWNER : owner;
        return o + "@" + workspaceId();
    }

    /** The auto durable memory note for an owner (updated from compactions), or null if none. */
    public String get(String owner) {
        if (!db.available()) return null;
        try {
            List<String> rows = db.query("SELECT note FROM memory WHERE owner=?",
                    rs -> rs.getString(1), key(owner));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[memory] load failed: " + e.getMessage());
            return null;
        }
    }

    /** Curated, pinned facts for an owner (never overwritten by auto write-back), or "" if none. */
    public String pinned(String owner) {
        if (!db.available()) return "";
        try {
            List<String> rows = db.query("SELECT pinned FROM memory WHERE owner=?",
                    rs -> rs.getString(1), key(owner));
            String p = rows.isEmpty() ? null : rows.get(0);
            return p == null ? "" : p;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The memory actually seeded into a new session: pinned facts first, then the auto note, with
     * duplicate lines removed (case-insensitive). Pinned facts win and are kept verbatim.
     */
    public String effective(String owner) {
        String pinned = pinned(owner);
        String note = get(owner);
        return dedupeLines((pinned == null ? "" : pinned) + "\n" + (note == null ? "" : note));
    }

    /** Drop blank/duplicate lines (case-insensitive), preserving first occurrence and order. */
    static String dedupeLines(String text) {
        if (text == null) return "";
        java.util.LinkedHashMap<String, String> seen = new java.util.LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            seen.putIfAbsent(line.toLowerCase(java.util.Locale.ROOT), line);
        }
        return String.join("\n", seen.values());
    }

    /** Store (replace) the AUTO durable memory note for an owner. Blank notes are ignored so an empty
     *  compaction never wipes a curated note; use {@link #setNote} for manual edits that may clear it. */
    public void save(String owner, String note) {
        if (!db.available() || note == null || note.isBlank()) return;
        upsertNote(owner, note);
    }

    /** Manually set the AUTO note exactly (may be empty, to clear the auto part while keeping pins). */
    public void setNote(String owner, String note) {
        if (!db.available()) return;
        upsertNote(owner, note == null ? "" : note);
    }

    private void upsertNote(String owner, String note) {
        try {
            db.update("INSERT INTO memory(owner, note, updated_at) VALUES(?,?,?) "
                            + "ON CONFLICT(owner) DO UPDATE SET note=excluded.note, updated_at=excluded.updated_at",
                    key(owner), note, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[memory] save failed: " + e.getMessage());
        }
    }

    /** Pin a fact (added to the curated set if not already present, case-insensitive). */
    public void addPin(String owner, String fact) {
        if (!db.available() || fact == null || fact.isBlank()) return;
        String merged = dedupeLines(pinned(owner) + "\n" + fact.strip());
        setPinned(owner, merged);
    }

    /** Remove a pinned fact (exact line match, case-insensitive). */
    public void removePin(String owner, String fact) {
        if (!db.available() || fact == null) return;
        String target = fact.strip().toLowerCase(java.util.Locale.ROOT);
        StringBuilder kept = new StringBuilder();
        for (String line : pinned(owner).split("\n")) {
            if (line.strip().toLowerCase(java.util.Locale.ROOT).equals(target)) continue;
            if (!line.isBlank()) kept.append(line.strip()).append("\n");
        }
        setPinned(owner, kept.toString().strip());
    }

    /** Replace the whole pinned set (used by addPin/removePin and a direct edit). */
    public void setPinned(String owner, String pinned) {
        if (!db.available()) return;
        try {
            // ensure a row exists, then update pinned (note defaults to empty if new)
            db.update("INSERT INTO memory(owner, note, updated_at, pinned) VALUES(?,?,?,?) "
                            + "ON CONFLICT(owner) DO UPDATE SET pinned=excluded.pinned, updated_at=excluded.updated_at",
                    key(owner), "", System.currentTimeMillis(), pinned == null ? "" : pinned);
        } catch (Exception e) {
            log.warn("[memory] pin failed: " + e.getMessage());
        }
    }

    /** When the durable note for an owner was last updated (epoch ms), or 0 if none. */
    public long updatedAt(String owner) {
        if (!db.available()) return 0;
        try {
            List<Long> rows = db.query("SELECT updated_at FROM memory WHERE owner=?",
                    rs -> rs.getLong(1), key(owner));
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Clear the durable note for an owner. */
    public void clear(String owner) {
        if (!db.available()) return;
        try {
            db.update("DELETE FROM memory WHERE owner=?", key(owner));
        } catch (Exception e) {
            log.warn("[memory] clear failed: " + e.getMessage());
        }
    }
}
