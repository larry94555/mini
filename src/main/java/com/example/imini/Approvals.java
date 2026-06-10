package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Remote approvals: when a run in ASK mode needs a decision (a gated tool, or "continue past the
 * deadline?"), the engine thread parks a pending request here and blocks on a future. A separate HTTP
 * request (POST /approve) resolves it; the UI learns about it instantly via an SSE "approval" event
 * and/or by polling GET /approvals. On timeout the configured default action (usually deny) is used,
 * so a run never blocks forever waiting for a human who isn't there.
 */
@Component
public class Approvals {

    public record Pending(String id, String sessionId, String tool, String args, long createdAt) {}

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<String>> futures = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Park a decision and block until resolved or timeout. Returns the decision string
     * (allow | always | deny), or {@code timeoutAction} if no one answered in time.
     */
    public String await(String sessionId, String tool, String args, long timeoutMs, String timeoutAction) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Pending p = new Pending(id, sessionId, tool, args, System.currentTimeMillis());
        CompletableFuture<String> f = new CompletableFuture<>();
        pending.put(id, p);
        futures.put(id, f);

        // notify the current run's client (SSE "approval" event) so the UI can show buttons
        try {
            SessionContext.sink().event("approval", mapper.writeValueAsString(Map.of(
                    "id", id, "sessionId", sessionId, "tool", tool, "args", args)));
        } catch (Exception ignore) {
            // best effort; the UI can also poll GET /approvals
        }

        try {
            return timeoutMs <= 0 ? f.get() : f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return timeoutAction;   // timeout / interruption -> safe default
        } finally {
            pending.remove(id);
            futures.remove(id);
        }
    }

    /** Resolve a pending approval. Returns false if the id is unknown (already resolved/expired). */
    public boolean resolve(String id, String decision) {
        CompletableFuture<String> f = futures.get(id);
        if (f == null) return false;
        f.complete(decision == null ? "deny" : decision.trim().toLowerCase());
        return true;
    }

    /** Pending approvals for a session (or all if sessionId is null), as plain maps for JSON. */
    public List<Map<String, Object>> list(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pending p : pending.values()) {
            if (sessionId == null || sessionId.equals(p.sessionId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.id());
                m.put("sessionId", p.sessionId());
                m.put("tool", p.tool());
                m.put("args", p.args());
                m.put("age_ms", System.currentTimeMillis() - p.createdAt());
                out.add(m);
            }
        }
        return out;
    }
}
