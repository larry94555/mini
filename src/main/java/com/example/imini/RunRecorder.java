package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records mutating tool calls (write/edit/patch/run_command/...) during a run, attributing each to the
 * session and -- inside a plan -- the current step. Two outputs: (1) a tool-call-level entry in the
 * {@link AuditLog} (so the audit shows WHAT was done, not just that a run started), and (2) a per-step
 * transcript persisted to the {@code plan_steps} table and surfaced at {@code GET /plan}.
 *
 * Step boundaries are driven from the checklist itself: the executor flips one step to
 * {@code in_progress} at a time, and {@link AgentLoop} calls {@link #syncStep} on each change.
 */
@Component
public class RunRecorder {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunRecorder.class);

    private final AuditLog audit;
    private final SessionStore sessions;
    private final Database db;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.audit.tool-calls:true}")
    private boolean enabled;

    private record Active(int stepIndex, List<ToolCall> calls) {}

    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, List<String>>> mem = new ConcurrentHashMap<>(); // fallback
    private final Map<String, Set<String>> changed = new ConcurrentHashMap<>(); // edit-trust: paths touched

    public RunRecorder(AuditLog audit, SessionStore sessions, Database db) {
        this.audit = audit;
        this.sessions = sessions;
        this.db = db;
    }

    private static boolean isPathMutator(String tool) {
        return "write_file".equals(tool) || "edit_file".equals(tool) || "apply_patch".equals(tool);
    }

    /** Paths mutated since the last {@link #beginRun}/{@link #beginEdits} for this session. */
    public Set<String> changedPaths(String sessionId) {
        return sessionId == null ? Set.of() : Set.copyOf(changed.getOrDefault(sessionId, Set.of()));
    }

    /** Reset only the edit-trust path tracking for a session (used by non-plan runs). */
    public void beginEdits(String sessionId) {
        if (sessionId != null) changed.remove(sessionId);
    }

    /** Index of the single {@code in_progress} step, or -1. Pure. */
    public static int activeStep(List<TodoStore.Item> items) {
        if (items == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if ("in_progress".equals(items.get(i).status())) return i;
        }
        return -1;
    }

    /** Start a fresh transcript for a new plan run (clears prior per-step records for the session). */
    public void beginRun(String sessionId) {
        if (!enabled || sessionId == null) return;
        active.remove(sessionId);
        mem.remove(sessionId);
        changed.remove(sessionId);
        try {
            if (db.available()) db.update("DELETE FROM plan_steps WHERE session_id=?", sessionId);
        } catch (Exception e) {
            log.debug("[recorder] clear failed: " + e.getMessage());
        }
    }

    /** Move the active step to {@code stepIndex} (or -1 for none), flushing the previous step's calls. */
    public void syncStep(String sessionId, int stepIndex) {
        if (!enabled || sessionId == null) return;
        Active a = active.get(sessionId);
        int cur = (a == null) ? -1 : a.stepIndex();
        if (stepIndex == cur) return;
        if (a != null) flush(sessionId, a);
        if (stepIndex >= 0) active.put(sessionId, new Active(stepIndex, new ArrayList<>()));
        else active.remove(sessionId);
    }

    /** Record one tool invocation. Only mutating tools are kept (reads would be noise). */
    public void record(String sessionId, String tool, boolean mutating, Map<String, Object> args, String result) {
        if (!mutating || sessionId == null) return;
        if (isPathMutator(tool) && args != null && args.get("path") != null) {
            changed.computeIfAbsent(sessionId, k -> new CopyOnWriteArraySet<>()).add(String.valueOf(args.get("path")));
        }
        if (!enabled) return;
        String summary = ToolCall.summarize(tool, args);
        String outcome = ToolCall.outcome(result);
        Active a = active.get(sessionId);
        if (a != null) a.calls().add(new ToolCall(System.currentTimeMillis(), tool, summary, outcome));
        String user = sessions.owner(sessionId);
        String target = "session:" + sessionId + (a != null ? " step:" + (a.stepIndex() + 1) : "");
        audit.record(user == null ? "anonymous" : user, "tool:" + tool, target,
                outcome + (summary.isBlank() ? "" : " " + summary));
    }

    /** stepIndex -> tool-call lines for a session (persisted + the in-flight step). For GET /plan. */
    public Map<Integer, List<String>> transcript(String sessionId) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        if (sessionId == null) return out;
        try {
            if (db.available()) {
                List<int[]> ignore = db.query("SELECT step_index, tools FROM plan_steps WHERE session_id=? "
                        + "ORDER BY step_index", rs -> {
                    try {
                        int idx = rs.getInt(1);
                        @SuppressWarnings("unchecked")
                        List<String> lines = mapper.readValue(rs.getString(2), List.class);
                        out.put(idx, lines);
                    } catch (Exception e) {
                        // skip malformed row
                    }
                    return new int[0];
                }, sessionId);
            } else {
                out.putAll(mem.getOrDefault(sessionId, Map.of()));
            }
        } catch (Exception e) {
            log.debug("[recorder] transcript load failed: " + e.getMessage());
        }
        Active a = active.get(sessionId);
        if (a != null) out.put(a.stepIndex(), lines(a.calls()));
        return out;
    }

    private void flush(String sessionId, Active a) {
        List<String> lines = lines(a.calls());
        try {
            if (db.available()) {
                String json = mapper.writeValueAsString(lines);
                db.update("INSERT INTO plan_steps(session_id, step_index, tools, updated_at) VALUES(?,?,?,?) "
                        + "ON CONFLICT(session_id, step_index) DO UPDATE SET tools=excluded.tools, "
                        + "updated_at=excluded.updated_at", sessionId, a.stepIndex(), json,
                        System.currentTimeMillis());
            } else {
                mem.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(a.stepIndex(), lines);
            }
        } catch (Exception e) {
            log.debug("[recorder] flush failed: " + e.getMessage());
        }
    }

    private static List<String> lines(List<ToolCall> calls) {
        List<String> out = new ArrayList<>();
        for (ToolCall c : calls) out.add(c.line());
        return out;
    }
}
