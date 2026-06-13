package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists the current plan (goal + checklist with per-step status) per session, so a plan-mode run
 * survives a restart, can be inspected at {@code GET /plan}, and resumed from the first not-completed
 * step. Stored in SQLite (table {@code plans}, one row per session) with an in-memory fallback.
 */
@Component
public class PlanStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlanStore.class);

    public record Saved(String goal, List<TodoStore.Item> items) {}

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Saved> mem = new ConcurrentHashMap<>(); // fallback

    public PlanStore(Database db) {
        this.db = db;
    }

    /** Upsert the plan for a session (goal + current checklist). Best-effort. */
    public void save(String sessionId, String goal, List<TodoStore.Item> items) {
        if (sessionId == null || items == null) return;
        try {
            String json = mapper.writeValueAsString(Planner.planPayload(items));
            if (db.available()) {
                db.update("INSERT INTO plans(session_id, goal, steps, updated_at) VALUES(?,?,?,?) "
                        + "ON CONFLICT(session_id) DO UPDATE SET goal=excluded.goal, steps=excluded.steps, "
                        + "updated_at=excluded.updated_at", sessionId, goal, json, System.currentTimeMillis());
            } else {
                mem.put(sessionId, new Saved(goal, List.copyOf(items)));
            }
        } catch (Exception e) {
            log.warn("[plan] could not save plan for '" + sessionId + "': " + e.getMessage());
        }
    }

    /** The saved plan for a session, or null if none. */
    @SuppressWarnings("unchecked")
    public Saved load(String sessionId) {
        if (sessionId == null) return null;
        try {
            if (db.available()) {
                List<Saved> rows = db.query("SELECT goal, steps FROM plans WHERE session_id=?", rs -> {
                    try {
                        List<Map<String, String>> payload = mapper.readValue(rs.getString(2), List.class);
                        return new Saved(rs.getString(1), Planner.itemsFromPayload(payload));
                    } catch (Exception e) {
                        return new Saved(rs.getString(1), List.of());
                    }
                }, sessionId);
                return rows.isEmpty() ? null : rows.get(0);
            }
            return mem.get(sessionId);
        } catch (Exception e) {
            log.warn("[plan] could not load plan for '" + sessionId + "': " + e.getMessage());
            return null;
        }
    }
}
