package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small queue of member-submitted skill proposals awaiting admin review. A member submits a
 * {name, description, body}; an admin lists the pending ones and approves (which saves the skill) or
 * rejects. Backed by the {@code skill_requests} table, with an in-memory fallback when no DB is present.
 */
@Component
public class SkillRequests {

    private static final Logger log = LoggerFactory.getLogger(SkillRequests.class);

    private final Database db;
    private final Map<String, Map<String, Object>> mem = new ConcurrentHashMap<>();

    public SkillRequests(Database db) {
        this.db = db;
    }

    /** Queue a proposal; returns its id. */
    public String submit(String requester, String name, String description, String body) {
        String id = "req-" + UUID.randomUUID().toString().substring(0, 8);
        long now = System.currentTimeMillis();
        if (db != null && db.available()) {
            try {
                db.update("INSERT INTO skill_requests(id, requester, name, description, body, status, created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", id, requester, name, description, body, "pending", now);
                return id;
            } catch (Exception e) {
                log.warn("[skill-req] insert failed: " + e.getMessage());
            }
        }
        Map<String, Object> row = row(id, requester, name, description, body, "pending", now);
        mem.put(id, row);
        return id;
    }

    /** Requests with the given status (or all when status is null/blank), newest first. */
    public List<Map<String, Object>> list(String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (db != null && db.available()) {
            try {
                String sql = "SELECT id, requester, name, description, body, status, created_at FROM skill_requests"
                        + (status == null || status.isBlank() ? "" : " WHERE status=?")
                        + " ORDER BY created_at DESC";
                Object[] args = (status == null || status.isBlank()) ? new Object[0] : new Object[]{status};
                return db.query(sql, rs -> {
                    try {
                        return row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getLong(7));
                    } catch (Exception e) {
                        return null;
                    }
                }, args);
            } catch (Exception e) {
                log.warn("[skill-req] list failed: " + e.getMessage());
                return out;
            }
        }
        for (Map<String, Object> r : mem.values()) {
            if (status == null || status.isBlank() || status.equals(r.get("status"))) out.add(r);
        }
        out.sort((a, b) -> Long.compare((long) b.get("createdAt"), (long) a.get("createdAt")));
        return out;
    }

    public Map<String, Object> get(String id) {
        if (db != null && db.available()) {
            List<Map<String, Object>> r = db.query(
                    "SELECT id, requester, name, description, body, status, created_at FROM skill_requests WHERE id=?",
                    rs -> {
                        try {
                            return row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    rs.getString(5), rs.getString(6), rs.getLong(7));
                        } catch (Exception e) {
                            return null;
                        }
                    }, id);
            return r.isEmpty() ? null : r.get(0);
        }
        return mem.get(id);
    }

    public boolean setStatus(String id, String status) {
        if (db != null && db.available()) {
            try {
                return db.update("UPDATE skill_requests SET status=? WHERE id=?", status, id) > 0;
            } catch (Exception e) {
                log.warn("[skill-req] setStatus failed: " + e.getMessage());
                return false;
            }
        }
        Map<String, Object> r = mem.get(id);
        if (r == null) return false;
        r.put("status", status);
        return true;
    }

    private static Map<String, Object> row(String id, String requester, String name, String description,
                                           String body, String status, long createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("requester", requester);
        m.put("name", name);
        m.put("description", description);
        m.put("body", body);
        m.put("status", status);
        m.put("createdAt", createdAt);
        return m;
    }
}
