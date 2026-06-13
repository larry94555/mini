package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a per-session HISTORY of completed plans, each archived with its final checklist (steps +
 * statuses), the per-step tool transcript, and the coding report. So a session accumulates an
 * inspectable record of past goals and what was done -- listed at {@code GET /plans} and fetched at
 * {@code GET /plan?n=<seq>}. SQLite ({@code plan_history}) with an in-memory fallback.
 */
@Component
public class PlanHistory {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlanHistory.class);

    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.plan.history-max:20}")
    private int maxHistory;

    private record Stored(int seq, String goal, long createdAt, int stepCount, String summary,
                          String stepsJson, String report) {}

    private final Map<String, List<Stored>> mem = new ConcurrentHashMap<>(); // fallback

    public PlanHistory(Database db) {
        this.db = db;
    }

    /** A concise status roll-up of a finished checklist (pure). */
    public static String summarize(List<TodoStore.Item> items) {
        int done = 0, failed = 0, other = 0;
        for (TodoStore.Item it : items) {
            switch (it.status() == null ? "" : it.status()) {
                case "completed" -> done++;
                case "failed" -> failed++;
                default -> other++;
            }
        }
        StringBuilder sb = new StringBuilder().append(items.size()).append(" steps: ").append(done).append(" done");
        if (failed > 0) sb.append(", ").append(failed).append(" failed");
        if (other > 0) sb.append(", ").append(other).append(" pending");
        return sb.toString();
    }

    /** Archive a completed plan as the next history entry for the session. Best-effort. */
    public void archive(String sessionId, String goal, List<TodoStore.Item> items,
                        Map<Integer, List<String>> transcript, String report) {
        if (sessionId == null || items == null || items.isEmpty()) return;
        try {
            String stepsJson = mapper.writeValueAsString(Planner.planPayload(items, transcript));
            String summary = summarize(items);
            String rpt = report == null ? "" : report;
            long now = System.currentTimeMillis();
            if (db.available()) {
                int seq = nextSeq(sessionId);
                db.update("INSERT INTO plan_history(session_id, seq, goal, steps, report, step_count, "
                        + "summary, created_at) VALUES(?,?,?,?,?,?,?,?)",
                        sessionId, seq, goal, stepsJson, rpt, items.size(), summary, now);
                if (maxHistory > 0) {
                    db.update("DELETE FROM plan_history WHERE session_id=? AND seq<=?", sessionId, seq - maxHistory);
                }
            } else {
                List<Stored> list = mem.computeIfAbsent(sessionId, k -> new ArrayList<>());
                int seq = list.isEmpty() ? 1 : list.get(list.size() - 1).seq() + 1;
                list.add(new Stored(seq, goal, now, items.size(), summary, stepsJson, rpt));
                while (maxHistory > 0 && list.size() > maxHistory) list.remove(0);
            }
        } catch (Exception e) {
            log.warn("[plan-history] archive failed for '" + sessionId + "': " + e.getMessage());
        }
    }

    /** Newest-first summaries of the session's archived plans. */
    public List<Map<String, Object>> list(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sessionId == null) return out;
        try {
            if (db.available()) {
                out = db.query("SELECT seq, goal, step_count, summary, created_at FROM plan_history "
                        + "WHERE session_id=? ORDER BY seq DESC", rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    try {
                        m.put("seq", rs.getInt(1));
                        m.put("goal", rs.getString(2));
                        m.put("stepCount", rs.getInt(3));
                        m.put("summary", rs.getString(4));
                        m.put("createdAt", rs.getLong(5));
                    } catch (Exception ignore) {}
                    return m;
                }, sessionId);
            } else {
                List<Stored> list = mem.getOrDefault(sessionId, List.of());
                for (int i = list.size() - 1; i >= 0; i--) {
                    Stored s = list.get(i);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("seq", s.seq());
                    m.put("goal", s.goal());
                    m.put("stepCount", s.stepCount());
                    m.put("summary", s.summary());
                    m.put("createdAt", s.createdAt());
                    out.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("[plan-history] list failed: " + e.getMessage());
        }
        return out;
    }

    /** A single archived plan (goal + steps with tools + report), or null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String sessionId, int seq) {
        if (sessionId == null) return null;
        try {
            String goal = null, stepsJson = null, report = null;
            long createdAt = 0;
            if (db.available()) {
                List<String[]> rows = db.query("SELECT goal, steps, report, created_at FROM plan_history "
                        + "WHERE session_id=? AND seq=?", rs -> {
                    try {
                        return new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                                String.valueOf(rs.getLong(4))};
                    } catch (Exception e) {
                        return null;
                    }
                }, sessionId, seq);
                if (rows.isEmpty() || rows.get(0) == null) return null;
                goal = rows.get(0)[0];
                stepsJson = rows.get(0)[1];
                report = rows.get(0)[2];
                createdAt = Long.parseLong(rows.get(0)[3]);
            } else {
                Stored found = null;
                for (Stored s : mem.getOrDefault(sessionId, List.of())) if (s.seq() == seq) found = s;
                if (found == null) return null;
                goal = found.goal();
                stepsJson = found.stepsJson();
                report = found.report();
                createdAt = found.createdAt();
            }
            List<Object> steps = stepsJson == null ? List.of() : mapper.readValue(stepsJson, List.class);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("goal", goal == null ? "" : goal);
            m.put("createdAt", createdAt);
            m.put("steps", steps);
            m.put("report", report == null ? "" : report);
            return m;
        } catch (Exception e) {
            log.warn("[plan-history] get failed: " + e.getMessage());
            return null;
        }
    }

    private int nextSeq(String sessionId) {
        List<Integer> max = db.query("SELECT COALESCE(MAX(seq),0) FROM plan_history WHERE session_id=?",
                rs -> {
                    try { return rs.getInt(1); } catch (Exception e) { return 0; }
                }, sessionId);
        return (max.isEmpty() ? 0 : max.get(0)) + 1;
    }
}
