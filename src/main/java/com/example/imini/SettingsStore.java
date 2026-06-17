package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Durable key/value application settings (the {@code app_settings} table), so runtime changes -- e.g. the
 * token budget -- survive a restart. Falls back gracefully to the caller's default when persistence is
 * unavailable, so the app still runs without a database.
 */
@Component
public class SettingsStore {

    private final Database db;

    public SettingsStore(Database db) {
        this.db = db;
    }

    /** Read a string setting, or {@code def} if absent / no DB. */
    public String getString(String key, String def) {
        if (!db.available()) return def;
        List<String> r = db.query("SELECT value FROM app_settings WHERE key=?", rs -> rs.getString(1), key);
        return r.isEmpty() ? def : r.get(0);
    }

    /** Persist a string setting (upsert). No-op if persistence is unavailable. */
    public void setString(String key, String value) {
        if (!db.available()) return;
        db.update("INSERT INTO app_settings(key, value) VALUES(?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    public int getInt(String key, int def) {
        String v = getString(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public void setInt(String key, int value) {
        setString(key, Integer.toString(value));
    }
}
