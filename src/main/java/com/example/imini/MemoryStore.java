package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
    @Value("${agent.memory-inject-max:12}") private int injectMax; // max durable facts seeded per session

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
            List<String> facts = db.query(
                    "SELECT fact FROM memory_pins WHERE scope=? ORDER BY created_at, rowid",
                    rs -> rs.getString(1), key(owner));
            return String.join("\n", facts);
        } catch (Exception e) {
            return "";
        }
    }

    /** Pinned facts with provenance: {@code [{fact, source, createdAt}]}, oldest first. */
    public java.util.List<Map<String, Object>> pinsDetailed(String owner) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        if (!db.available()) return out;
        try {
            return db.query(
                    "SELECT fact, source, created_at FROM memory_pins WHERE scope=? ORDER BY created_at, rowid",
                    rs -> {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("fact", rs.getString(1));
                        m.put("source", rs.getString(2));
                        m.put("createdAt", rs.getLong(3));
                        return m;
                    }, key(owner));
        } catch (Exception e) {
            return out;
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

    /** Pin a fact with provenance (no-op if already pinned, case-insensitive on the fact). */
    public void addPin(String owner, String fact, String source) {
        if (!db.available() || fact == null || fact.isBlank()) return;
        String src = (source == null || source.isBlank()) ? "manual" : source.strip();
        try {
            db.update("INSERT OR IGNORE INTO memory_pins(scope, fact, source, created_at) VALUES(?,?,?,?)",
                    key(owner), fact.strip(), src, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[memory] pin failed: " + e.getMessage());
        }
    }

    /** Remove a pinned fact (exact line match, case-insensitive). */
    public void removePin(String owner, String fact) {
        if (!db.available() || fact == null) return;
        try {
            db.update("DELETE FROM memory_pins WHERE scope=? AND lower(fact)=lower(?)", key(owner), fact.strip());
        } catch (Exception e) {
            log.warn("[memory] unpin failed: " + e.getMessage());
        }
    }

    /** Merge in a list of pins with provenance (used by bundle import); existing pins are kept. */
    public void importPins(String owner, java.util.List<Map<String, Object>> pins) {
        if (!db.available() || pins == null) return;
        for (Map<String, Object> p : pins) {
            Object f = p.get("fact");
            if (f == null || String.valueOf(f).isBlank()) continue;
            Object src = p.get("source");
            Object ts = p.get("createdAt");
            long created = (ts instanceof Number) ? ((Number) ts).longValue() : System.currentTimeMillis();
            try {
                db.update("INSERT OR IGNORE INTO memory_pins(scope, fact, source, created_at) VALUES(?,?,?,?)",
                        key(owner), String.valueOf(f).strip(),
                        src == null ? "imported" : String.valueOf(src), created);
            } catch (Exception e) {
                log.warn("[memory] import pin failed: " + e.getMessage());
            }
        }
    }

    /**
     * The memory to seed into a new session, RELEVANCE-RANKED to a query: all pinned facts (always kept),
     * plus the top auto-note facts scored by lexical overlap with the query, capped at
     * {@code agent.memory-inject-max} total and de-duplicated. Reuses {@link RetrievalService}'s pure
     * lexical scorer so it needs no embedding server. With a blank query (or no overlap) it keeps the
     * first auto facts in note order.
     */
    public String relevantSeed(String owner, String query) {
        java.util.List<String> pins = splitLines(pinned(owner));
        java.util.Set<String> pinSet = new java.util.HashSet<>();
        for (String p : pins) pinSet.add(p.toLowerCase(java.util.Locale.ROOT));

        java.util.List<String> auto = new java.util.ArrayList<>();
        for (String l : splitLines(get(owner))) {
            if (!pinSet.contains(l.toLowerCase(java.util.Locale.ROOT))) auto.add(l);
        }
        java.util.List<String> qt = RetrievalService.tokenize(query == null ? "" : query);
        if (!qt.isEmpty()) {
            auto.sort((a, b) -> Double.compare(
                    RetrievalService.lexicalScore(qt, b), RetrievalService.lexicalScore(qt, a)));
        }
        int room = Math.max(0, injectMax - pins.size());
        java.util.List<String> combined = new java.util.ArrayList<>(pins);
        combined.addAll(auto.subList(0, Math.min(room, auto.size())));
        return dedupeLines(String.join("\n", combined));
    }

    private static java.util.List<String> splitLines(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String l : text.split("\n")) {
            String s = l.strip();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
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
