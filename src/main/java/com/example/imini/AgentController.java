package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP surface.
 *
 *   Blocking (returns JSON when the run finishes):
 *     POST /ask          {"question":"...","mode":?}                     one-shot, no memory
 *     POST /chat         {"sessionId":"?","message":"...","mode":?}      multi-turn; returns sessionId
 *
 *   Streaming (Server-Sent Events; tokens arrive live):
 *     POST /ask/stream   {"question":"...","mode":?}
 *     POST /chat/stream  {"sessionId":"?","message":"...","mode":?}
 *       events: session | token | log | answer | done | error
 *
 *   Per-session control (carry the sessionId):
 *     POST /interrupt    {"sessionId":"..."}                             stop that run
 *     POST /steer        {"sessionId":"...","message":"..."}             inject guidance into that run
 *     GET  /todos?sessionId=...                                         that session's checklist
 *
 *   GET /sessions, POST /rewind {sessionId}, GET /checkpoints?sessionId=, GET /runs.
 *   POST /index (build retrieval index), GET /memory?q=&k= (search the index).
 *   GET /health (open), GET /metrics (observability snapshot).
 *   GET /session?id= (one session's messages, for the web UI at / ).
 *   GET /approvals?sessionId=, POST /approve {id,decision} (remote ASK-mode approvals).
 *
 * Concurrency is bounded to the model's slot count by RunService (see GET /runs). mode = ask
 * (default) | auto | plan. NOTE: ASK-mode permission/deadline prompts are answered on the SERVER
 * console (single operator); concurrent remote users should use auto mode + permissions.json rules.
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final SessionStore sessions;
    private final CheckpointStore checkpoints;
    private final TodoStore todos;
    private final InterruptService interrupt;
    private final RunService runService;
    private final RetrievalService retrieval;
    private final Metrics metrics;
    private final Approvals approvals;
    private final AuditLog audit;
    private final PlanStore plans;
    private final RunRecorder recorder;
    private final PlanHistory history;

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints,
                           TodoStore todos, InterruptService interrupt, RunService runService,
                           RetrievalService retrieval, Metrics metrics, Approvals approvals,
                           AuditLog audit, PlanStore plans, RunRecorder recorder, PlanHistory history) {
        this.loop = loop;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.todos = todos;
        this.interrupt = interrupt;
        this.runService = runService;
        this.retrieval = retrieval;
        this.metrics = metrics;
        this.approvals = approvals;
        this.audit = audit;
        this.plans = plans;
        this.recorder = recorder;
        this.history = history;
    }

    // ---- blocking ----------------------------------------------------------

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = "oneshot-" + UUID.randomUUID().toString().substring(0, 8);
        final Mode mode = parseMode(body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        final String q = body.getOrDefault("question", "");
        sessions.claim(sessionId, currentUser());
        audit.record(currentUser(), planAction("ask", plan, resume), "session:" + sessionId, "started");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        try {
            String answer = runService.runBounded(() ->
                    (plan && resume) ? loop.resumePlan(sessionId, mode, new ConsoleSink())
                         : plan ? loop.runPlan(sessionId, q, mode, new ConsoleSink())
                         : loop.run(sessionId, q, mode, new ConsoleSink()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            metrics.recordRun(ms, true);
            metrics.logRun("/ask", sessionId, null, ms, true);
            return Map.of("answer", answer);
        } catch (Exception e) {
            metrics.recordRun((System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        }
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        final Mode mode = parseMode(body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        audit.record(currentUser(), planAction("chat", plan, resume), "session:" + sessionId, "started");
        final String message = body.getOrDefault("message", "");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        try {
            String answer = runService.runBounded(() ->
                    (plan && resume) ? loop.resumePlan(sessionId, mode, new ConsoleSink())
                         : plan ? loop.runPlan(sessionId, message, mode, new ConsoleSink())
                         : loop.chat(sessionId, message, mode, new ConsoleSink()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            metrics.recordRun(ms, true);
            metrics.logRun("/chat", sessionId, null, ms, true);
            return Map.of("sessionId", sessionId, "answer", answer);
        } catch (Exception e) {
            metrics.recordRun((System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        }
    }

    // ---- streaming (SSE) ---------------------------------------------------

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        final Mode mode = parseMode(body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        audit.record(currentUser(), planAction("chat/stream", plan, resume), "session:" + sessionId, "started");
        final String message = body.getOrDefault("message", "");
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            try {
                send(emitter, "session", sessionId);
                String answer = runService.runBounded(() ->
                        (plan && resume) ? loop.resumePlan(sessionId, mode, sink)
                             : plan ? loop.runPlan(sessionId, message, mode, sink)
                             : loop.chat(sessionId, message, mode, sink));
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                metrics.recordRun(ms, true);
                metrics.logRun("/chat/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                metrics.recordRun((System.nanoTime() - t0) / 1_000_000L, false);
                send(emitter, "error", String.valueOf(e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody Map<String, String> body) {
        final String sessionId = "oneshot-" + UUID.randomUUID().toString().substring(0, 8);
        final Mode mode = parseMode(body.get("mode"));
        final String q = body.getOrDefault("question", "");
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        sessions.claim(sessionId, currentUser());
        audit.record(currentUser(), planAction("ask/stream", plan, resume), "session:" + sessionId, "started");
        SseEmitter emitter = new SseEmitter(0L);
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            try {
                send(emitter, "session", sessionId);
                String answer = runService.runBounded(() ->
                        (plan && resume) ? loop.resumePlan(sessionId, mode, sink)
                             : plan ? loop.runPlan(sessionId, q, mode, sink)
                             : loop.run(sessionId, q, mode, sink));
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                metrics.recordRun(ms, true);
                metrics.logRun("/ask/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                metrics.recordRun((System.nanoTime() - t0) / 1_000_000L, false);
                send(emitter, "error", String.valueOf(e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    // ---- per-session control ----------------------------------------------

    @PostMapping("/interrupt")
    public Map<String, String> interrupt(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        if (sessionId.isBlank()) return Map.of("result", "provide a sessionId to interrupt.");
        requireAccess(sessionId);
        interrupt.interrupt(sessionId);
        audit.record(currentUser(), "interrupt", "session:" + sessionId, "requested");
        return Map.of("result", "interrupt requested for session " + sessionId
                + "; it will stop at the next checkpoint.");
    }

    @PostMapping("/steer")
    public Map<String, String> steer(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String message = body.getOrDefault("message", "");
        if (sessionId.isBlank()) return Map.of("result", "provide a sessionId to steer.");
        requireAccess(sessionId);
        interrupt.steer(sessionId, message);
        audit.record(currentUser(), "steer", "session:" + sessionId, "queued");
        return Map.of("result", "steering queued for session " + sessionId + ": " + message);
    }

    @GetMapping("/plans")
    public Map<String, Object> plans(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        return Map.of("sessionId", sessionId, "plans", history.list(sessionId));
    }

    @GetMapping("/plan")
    public Map<String, Object> plan(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
                                    @RequestParam(name = "n", required = false) Integer n) {
        requireRead(sessionId);
        if (n != null) {
            Map<String, Object> archived = history.get(sessionId, n);
            return archived == null
                    ? Map.of("sessionId", sessionId, "seq", n, "goal", "", "steps", List.of())
                    : archived;
        }
        PlanStore.Saved saved = plans.load(sessionId);
        java.util.Map<Integer, List<String>> tx = recorder.transcript(sessionId);
        if (saved == null) return Map.of("sessionId", sessionId, "goal", "", "steps", List.of());
        List<Map<String, String>> base = Planner.planPayload(saved.items());
        List<Map<String, Object>> steps = new java.util.ArrayList<>();
        for (int i = 0; i < base.size(); i++) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(base.get(i));
            m.put("tools", tx.getOrDefault(i, List.of()));
            steps.add(m);
        }
        return Map.of("sessionId", sessionId, "goal", saved.goal() == null ? "" : saved.goal(),
                "steps", steps);
    }

    @GetMapping("/todos")
    public Map<String, Object> todos(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        return Map.of("sessionId", sessionId, "todos", todos.get(sessionId), "rendered", todos.render(sessionId));
    }

    // ---- status / misc -----------------------------------------------------

    @GetMapping("/sessions")
    public List<String> sessions() {
        Principal caller = RequestContext.current();
        return sessions.list().stream()
                .filter(id -> Ownership.canRead(caller, sessions.owner(id), sessions.readers(id)))
                .toList();
    }

    /** A single session's stored messages (for the UI to render prior history on switch). */
    @GetMapping("/session")
    public List<Map<String, Object>> session(@RequestParam(name = "id") String id) {
        requireRead(id);
        List<Map<String, Object>> h = sessions.get(id);
        return h == null ? List.of() : h;
    }

    @GetMapping("/runs")
    public Map<String, Integer> runs() {
        return Map.of("limit", runService.limit(), "active", runService.active(), "queued", runService.queued());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/me")
    public Map<String, String> me() {
        Principal p = RequestContext.current();
        return Map.of("user", p.user(), "role", p.role());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    /** Admin-only audit trail of privileged actions (newest first); filter by user/target. */
    @GetMapping("/audit")
    public List<AuditLog.Entry> audit(@RequestParam(name = "user", defaultValue = "") String user,
                                      @RequestParam(name = "target", defaultValue = "") String target,
                                      @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return audit.recent(user, target, limit);
    }

    // ---- remote approvals --------------------------------------------------

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(
            @RequestParam(name = "sessionId", defaultValue = "") String sessionId) {
        Principal caller = RequestContext.current();
        return approvals.list(sessionId.isBlank() ? null : sessionId).stream()
                .filter(a -> { String sid = String.valueOf(a.get("sessionId"));
                        return Ownership.canRead(caller, sessions.owner(sid), sessions.readers(sid)); })
                .toList();
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@RequestBody Map<String, String> body) {
        String id = body.getOrDefault("id", "");
        String decision = body.getOrDefault("decision", "deny"); // allow | always | deny
        String sid = approvals.sessionOf(id);
        if (sid != null) requireAccess(sid);
        boolean ok = approvals.resolve(id, decision);
        audit.record(currentUser(), "approve", "approval:" + id + (sid != null ? " session:" + sid : ""),
                (ok ? "resolved " : "unknown ") + decision);
        return Map.of("resolved", ok, "id", id, "decision", decision);
    }

    @PostMapping("/rewind")
    public Map<String, String> rewind(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        if (sessionId.isBlank()) return Map.of("result", "provide a sessionId to rewind.");
        requireAccess(sessionId);
        String result = checkpoints.rewindLast(sessionId);
        audit.record(currentUser(), "rewind", "session:" + sessionId, result);
        return Map.of("result", result);
    }

    @GetMapping("/checkpoints")
    public List<String> checkpoints(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        return checkpoints.list(sessionId);
    }

    // ---- sharing / ownership ----------------------------------------------

    /** Who can see a session: its owner and the users it is shared with. */
    @GetMapping("/shares")
    public Map<String, Object> shares(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        String owner = sessions.owner(sessionId);
        return Map.of("sessionId", sessionId, "owner", owner == null ? "" : owner,
                "readers", new java.util.ArrayList<>(sessions.readers(sessionId)));
    }

    /** Grant another user read access to a session (owner/admin only). */
    @PostMapping("/share")
    public Map<String, Object> share(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String user = body.getOrDefault("user", "");
        if (sessionId.isBlank() || user.isBlank()) return Map.of("error", "provide sessionId and user");
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        sessions.share(sessionId, user);
        audit.record(currentUser(), "share", "session:" + sessionId, "granted read to " + user);
        return Map.of("sessionId", sessionId, "readers", new java.util.ArrayList<>(sessions.readers(sessionId)));
    }

    /** Revoke a user's read access (owner/admin only). */
    @PostMapping("/unshare")
    public Map<String, Object> unshare(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String user = body.getOrDefault("user", "");
        if (sessionId.isBlank() || user.isBlank()) return Map.of("error", "provide sessionId and user");
        requireAccess(sessionId);
        sessions.unshare(sessionId, user);
        audit.record(currentUser(), "unshare", "session:" + sessionId, "revoked read from " + user);
        return Map.of("sessionId", sessionId, "readers", new java.util.ArrayList<>(sessions.readers(sessionId)));
    }

    /** Transfer ownership of a session to another user; the previous owner keeps read access. */
    @PostMapping("/transfer")
    public Map<String, Object> transfer(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String to = body.getOrDefault("to", "");
        if (sessionId.isBlank() || to.isBlank()) return Map.of("error", "provide sessionId and to");
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        String previous = sessions.transfer(sessionId, to);
        audit.record(currentUser(), "transfer", "session:" + sessionId,
                "owner -> " + to + (previous != null ? " (was " + previous + ")" : ""));
        return Map.of("sessionId", sessionId, "owner", to, "previousOwner", previous == null ? "" : previous,
                "readers", new java.util.ArrayList<>(sessions.readers(sessionId)));
    }

    // ---- retrieval / memory ------------------------------------------------

    @PostMapping("/index")
    public Map<String, String> index() {
        String result = retrieval.index();
        audit.record(currentUser(), "index", "workspace", result);
        return Map.of("result", result);
    }

    @GetMapping("/memory")
    public Map<String, String> memory(@RequestParam(name = "q") String q,
                                      @RequestParam(name = "k", defaultValue = "5") int k) {
        return Map.of("result", retrieval.search(q, k));
    }

    // ---- helpers -----------------------------------------------------------

    private String resolveSession(String s) {
        return (s == null || s.isBlank()) ? UUID.randomUUID().toString().substring(0, 8) : s;
    }

    private static String currentUser() {
        return RequestContext.current().user();
    }

    /** 403 unless the caller owns this session (or is admin, or the session is unowned). */
    private void requireAccess(String sessionId) {
        if (!Ownership.canAccess(RequestContext.current(), sessions.owner(sessionId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "session '" + sessionId + "' belongs to another user");
        }
    }

    /** 403 unless the caller can READ this session (owner/admin/unowned, or shared with them). */
    private void requireRead(String sessionId) {
        if (!Ownership.canRead(RequestContext.current(), sessions.owner(sessionId),
                sessions.readers(sessionId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "session '" + sessionId + "' is not shared with you");
        }
    }

    private static RunSink sseSink(SseEmitter emitter) {
        return new RunSink() {
            @Override public void token(String text) { send(emitter, "token", text); }
            @Override public void log(String line) { send(emitter, "log", line); }
            @Override public void event(String type, String data) { send(emitter, type, data); }
        };
    }

    private static void send(SseEmitter emitter, String name, String data) {
        try {
            // JSON-encode the payload (see Sse) so token spaces/newlines survive SSE framing.
            emitter.send(SseEmitter.event().name(name).data(Sse.encode(data)));
        } catch (Exception e) {
            // client disconnected or emitter completed; nothing to do
        }
    }

    private static String planAction(String base, boolean plan, boolean resume) {
        if (plan && resume) return base + "(resume)";
        if (plan) return base + "(plan)";
        return base;
    }

    private static boolean isPlan(String raw) {
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("yes"));
    }

    private Mode parseMode(String raw) {
        if (raw == null) return Mode.ASK;
        return switch (raw.trim().toLowerCase()) {
            case "auto" -> Mode.AUTO;
            case "plan" -> Mode.PLAN;
            default -> Mode.ASK;
        };
    }
}
