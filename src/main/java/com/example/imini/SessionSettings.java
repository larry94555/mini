package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable per-session settings (the {@code session_settings} table). Lets a session remember preferences
 * -- currently its default permission mode -- across restarts, layered under any explicit per-request
 * value (see {@link SessionSettingsResolver}). Falls back to no-op / empty when persistence is
 * unavailable, so the app still runs without a database.
 */
@Component
public class SessionSettings {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionSettings.class);

    private final Database db;

    public SessionSettings(Database db) {
        this.db = db;
    }

    /** A single setting value for a session, or null if unset / no DB. */
    public String get(String sessionId, String key) {
        if (!db.available() || sessionId == null || key == null) return null;
        try {
            List<String> r = db.query("SELECT value FROM session_settings WHERE session_id=? AND key=?",
                    rs -> rs.getString(1), sessionId, key.trim().toLowerCase());
            return r.isEmpty() ? null : r.get(0);
        } catch (Exception e) {
            log.warn("[session-settings] read " + key + " for " + sessionId + ": " + e.getMessage());
            return null;
        }
    }

    /** All settings for a session as key -> value. */
    public Map<String, String> all(String sessionId) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!db.available() || sessionId == null) return out;
        try {
            db.query("SELECT key, value FROM session_settings WHERE session_id=? ORDER BY key",
                    rs -> { out.put(rs.getString(1), rs.getString(2)); return null; }, sessionId);
        } catch (Exception e) {
            log.warn("[session-settings] list for " + sessionId + ": " + e.getMessage());
        }
        return out;
    }

    /** Set (upsert) a setting. The key/value should be validated/normalized by the caller first. */
    public void set(String sessionId, String key, String value) {
        if (!db.available() || sessionId == null || key == null || value == null) return;
        try {
            db.update("INSERT INTO session_settings(session_id, key, value) VALUES(?,?,?) "
                    + "ON CONFLICT(session_id, key) DO UPDATE SET value=excluded.value",
                    sessionId, key.trim().toLowerCase(), value);
        } catch (Exception e) {
            log.warn("[session-settings] set " + key + " for " + sessionId + ": " + e.getMessage());
        }
    }

    /** Remove a setting (clears the session's override; it falls back to the global default). */
    public void clear(String sessionId, String key) {
        if (!db.available() || sessionId == null || key == null) return;
        try {
            db.update("DELETE FROM session_settings WHERE session_id=? AND key=?",
                    sessionId, key.trim().toLowerCase());
        } catch (Exception e) {
            log.warn("[session-settings] clear " + key + " for " + sessionId + ": " + e.getMessage());
        }
    }
}
