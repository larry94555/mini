package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-turn history per session, now persisted in SQLite (table {@code sessions}) so a conversation
 * survives a restart. The whole message list is stored as a JSON blob in one row -- the same list the
 * engine works on (including compaction), so sessions stay small automatically. A small in-memory
 * cache fronts the DB and also serves as the fallback when persistence is unavailable.
 */
@Component
public class SessionStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionStore.class);


    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> owners = new ConcurrentHashMap<>(); // session_id -> owner

    public SessionStore(Database db) {
        this.db = db;
    }

    /** Returns stored history for a session, or null if it doesn't exist yet. */
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> get(String id) {
        if (cache.containsKey(id)) return cache.get(id);
        if (db.available()) {
            List<String> rows = db.query("SELECT messages FROM sessions WHERE session_id=?",
                    rs -> rs.getString(1), id);
            if (!rows.isEmpty()) {
                try {
                    List<Map<String, Object>> h = mapper.readValue(rows.get(0), List.class);
                    cache.put(id, h);
                    return h;
                } catch (Exception e) {
                    log.warn("[session] could not parse '" + id + "': " + e.getMessage());
                }
            }
        }
        return null;
    }

    public synchronized void save(String id, List<Map<String, Object>> history) {
        cache.put(id, history);
        if (db.available()) {
            try {
                String json = mapper.writeValueAsString(history);
                db.update("INSERT INTO sessions(session_id, messages, updated_at) VALUES(?,?,?) "
                                + "ON CONFLICT(session_id) DO UPDATE SET messages=excluded.messages, updated_at=excluded.updated_at",
                        id, json, System.currentTimeMillis());
            } catch (Exception e) {
                log.warn("[session] could not save '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Record the owner of a session the first time it is used (no-op if already owned). */
    public synchronized void claim(String id, String user) {
        if (id == null || id.isBlank() || user == null || user.isBlank()) return;
        if (owner(id) != null) return;
        owners.put(id, user);
        if (db.available()) {
            try {
                db.update("INSERT INTO session_owners(session_id, owner) VALUES(?,?) "
                        + "ON CONFLICT(session_id) DO NOTHING", id, user);
            } catch (Exception e) {
                log.warn("[session] could not set owner for '" + id + "': " + e.getMessage());
            }
        }
    }

    /** The owning user of a session, or null if unowned (legacy/new). */
    public synchronized String owner(String id) {
        if (owners.containsKey(id)) return owners.get(id);
        if (db.available()) {
            List<String> r = db.query("SELECT owner FROM session_owners WHERE session_id=?",
                    rs -> rs.getString(1), id);
            if (!r.isEmpty()) { owners.put(id, r.get(0)); return r.get(0); }
        }
        return null;
    }

    public synchronized List<String> list() {
        if (db.available()) {
            List<String> ids = db.query("SELECT session_id FROM sessions ORDER BY updated_at DESC",
                    rs -> rs.getString(1));
            for (String k : cache.keySet()) if (!ids.contains(k)) ids.add(k);
            return ids;
        }
        return new ArrayList<>(cache.keySet());
    }
}
