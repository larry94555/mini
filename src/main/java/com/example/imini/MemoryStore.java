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

    private final Database db;

    public MemoryStore(Database db) {
        this.db = db;
    }

    private static String owner(String owner) {
        return (owner == null || owner.isBlank()) ? DEFAULT_OWNER : owner;
    }

    /** The durable memory note for an owner, or null if none has been stored yet. */
    public String get(String owner) {
        if (!db.available()) return null;
        try {
            List<String> rows = db.query("SELECT note FROM memory WHERE owner=?",
                    rs -> rs.getString(1), owner(owner));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.warn("[memory] load failed: " + e.getMessage());
            return null;
        }
    }

    /** Store (replace) the durable memory note for an owner. Blank notes are ignored. */
    public void save(String owner, String note) {
        if (!db.available() || note == null || note.isBlank()) return;
        try {
            db.update("INSERT INTO memory(owner, note, updated_at) VALUES(?,?,?) "
                            + "ON CONFLICT(owner) DO UPDATE SET note=excluded.note, updated_at=excluded.updated_at",
                    owner(owner), note, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[memory] save failed: " + e.getMessage());
        }
    }

    /** When the durable note for an owner was last updated (epoch ms), or 0 if none. */
    public long updatedAt(String owner) {
        if (!db.available()) return 0;
        try {
            List<Long> rows = db.query("SELECT updated_at FROM memory WHERE owner=?",
                    rs -> rs.getLong(1), owner(owner));
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Clear the durable note for an owner. */
    public void clear(String owner) {
        if (!db.available()) return;
        try {
            db.update("DELETE FROM memory WHERE owner=?", owner(owner));
        } catch (Exception e) {
            log.warn("[memory] clear failed: " + e.getMessage());
        }
    }
}
