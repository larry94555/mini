package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final SkillService skills;
    private final SkillRequests skillRequests;
    private final ProjectContext project;
    private final InitService init;
    private final TokenBudgetService tokenBudget;
    private final LlamaClient llama;
    private final ScheduledTasks schedule;
    private final PluginService plugins;
    private final SessionSettings sessionSettings;
    private final WorkspaceService workspace;
    private final PreviewStore previews;
    private final BuiltinTools builtins;
    private final MemoryStore memory;
    private final ContextManager context;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints,
                           TodoStore todos, InterruptService interrupt, RunService runService,
                           RetrievalService retrieval, Metrics metrics, Approvals approvals,
                           AuditLog audit, PlanStore plans, RunRecorder recorder, PlanHistory history,
                           SkillService skills, SkillRequests skillRequests, ProjectContext project, InitService init,
                           PreviewStore previews, BuiltinTools builtins,
                           TokenBudgetService tokenBudget, LlamaClient llama, ScheduledTasks schedule,
                           PluginService plugins, SessionSettings sessionSettings,
                           WorkspaceService workspace, MemoryStore memory, ContextManager context) {
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
        this.skills = skills;
        this.skillRequests = skillRequests;
        this.project = project;
        this.init = init;
        this.tokenBudget = tokenBudget;
        this.llama = llama;
        this.schedule = schedule;
        this.plugins = plugins;
        this.sessionSettings = sessionSettings;
        this.workspace = workspace;
        this.previews = previews;
        this.builtins = builtins;
        this.memory = memory;
        this.context = context;
    }

    // ---- blocking ----------------------------------------------------------

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = "oneshot-" + UUID.randomUUID().toString().substring(0, 8);
        final Mode mode = parseMode(body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        final String q = body.getOrDefault("question", "");
        final String image = body.get("image");          // optional base64 or data: URL
        final String imageType = body.get("imageType");  // optional media type, e.g. image/png
        sessions.claim(sessionId, currentUser());
        audit.record(currentUser(), planAction("ask", plan, resume), "session:" + sessionId, "started");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        try {
            String answer = runService.runBounded(() ->
                    (image != null && !image.isBlank()) ? loop.run(sessionId, q, image, imageType, mode, new ConsoleSink())
                         : (plan && resume) ? loop.resumePlan(sessionId, mode, new ConsoleSink())
                         : plan ? loop.runPlan(sessionId, q, mode, new ConsoleSink())
                         : loop.run(sessionId, q, mode, new ConsoleSink()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            metrics.recordRun("/ask", sessionId, mode.name().toLowerCase(), ms, true);
            metrics.logRun("/ask", sessionId, null, ms, true);
            return Map.of("answer", answer);
        } catch (Exception e) {
            metrics.recordRun("/ask", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        }
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        final Mode mode = effectiveMode(sessionId, body.get("mode"));
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
            metrics.recordRun("/chat", sessionId, mode.name().toLowerCase(), ms, true);
            metrics.logRun("/chat", sessionId, null, ms, true);
            return Map.of("sessionId", sessionId, "answer", answer);
        } catch (Exception e) {
            metrics.recordRun("/chat", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        }
    }

    // ---- streaming (SSE) ---------------------------------------------------

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        sessions.claim(sessionId, currentUser());
        final Mode mode = effectiveMode(sessionId, body.get("mode"));
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
                metrics.recordRun("/chat/stream", sessionId, mode.name().toLowerCase(), ms, true);
                metrics.logRun("/chat/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                metrics.recordRun("/chat/stream", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
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
                metrics.recordRun("/ask/stream", sessionId, mode.name().toLowerCase(), ms, true);
                metrics.logRun("/ask/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                metrics.recordRun("/ask/stream", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
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

    /** Metrics in Prometheus text exposition format, for external scraping (admin only). */
    @GetMapping("/metrics/prom")
    public ResponseEntity<String> metricsProm() {
        requireAdmin();
        return ResponseEntity.ok()
                .header("Content-Type", PromFormat.CONTENT_TYPE)
                .body(PromFormat.render(metrics.snapshot()));
    }

    /**
     * Consolidated admin/observability snapshot: uptime, run counts + success rate, latency, concurrency,
     * top tool calls, scheduled-task and plugin/skill summaries, recent audit, and server capability flags.
     * One call powers the web-UI admin dashboard. Admin only.
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/admin/overview")
    public Map<String, Object> adminOverview(
            @RequestParam(name = "auditLimit", defaultValue = "10") int auditLimit) {
        requireAdmin();
        Map<String, Object> snap = metrics.snapshot();
        Map<String, Object> out = new java.util.LinkedHashMap<>();

        long uptimeMs = snap.get("uptime_ms") instanceof Number num ? num.longValue() : 0L;
        out.put("uptimeMs", uptimeMs);
        out.put("uptime", AdminFormat.humanizeUptime(uptimeMs));

        // runs: ok/failed + success rate, latency, live concurrency
        Map<String, Long> counters = (Map<String, Long>) snap.getOrDefault("counters", Map.of());
        long ok = counters.getOrDefault("runs_ok", 0L);
        long failed = counters.getOrDefault("runs_failed", 0L);
        Map<String, Object> runs = new java.util.LinkedHashMap<>();
        runs.put("ok", ok);
        runs.put("failed", failed);
        runs.put("started", counters.getOrDefault("runs_started", 0L));
        runs.put("successRate", AdminFormat.successRate(ok, failed));
        runs.put("latency", snap.get("run_latency"));
        runs.put("concurrency", snap.get("concurrency"));
        out.put("runs", runs);

        out.put("topTools", AdminFormat.topN(
                (Map<String, Long>) snap.getOrDefault("tool_calls_by_name", Map.of()), 8));
        out.put("requestsByKey", snap.get("requests_by_key"));
        out.put("approxOutputTokens", snap.get("approx_output_tokens"));

        // scheduled tasks summary
        int taskCount = 0, taskEnabled = 0;
        for (ScheduledTasks.Task t : schedule.list()) { taskCount++; if (t.enabled) taskEnabled++; }
        out.put("scheduledTasks", Map.of("total", taskCount, "enabled", taskEnabled));

        // plugin/skill counts + token budget + server capabilities
        out.put("content", plugins.summary());
        int ctx = llama.serverContext();
        out.put("server", Map.of(
                "contextTokens", ctx,
                "vision", llama.serverVision(),
                "promptCap", tokenBudget.promptCap(ctx),
                "tokenBudget", tokenBudget.budget()));

        out.put("recentRuns", metrics.recentRuns(10));
        out.put("recentAudit", audit.recent("", "", "", 0, Math.max(1, Math.min(50, auditLimit))));
        return out;
    }

    /** Recent runs (newest first): endpoint, session, resolved mode, duration, outcome. Admin only. */
    @GetMapping("/admin/runs")
    public List<Map<String, Object>> adminRuns(
            @RequestParam(name = "limit", defaultValue = "25") int limit,
            @RequestParam(name = "endpoint", defaultValue = "") String endpoint,
            @RequestParam(name = "outcome", defaultValue = "") String outcome,
            @RequestParam(name = "session", defaultValue = "") String session) {
        requireAdmin();
        return metrics.recentRuns(Math.max(1, Math.min(200, limit)), endpoint, outcome, session);
    }

    /** Recent runs for one session (newest first): endpoint, mode, latency, outcome. Session read access. */
    @GetMapping("/session/runs")
    public List<Map<String, Object>> sessionRuns(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        requireRead(sessionId);
        return metrics.recentRunsForSession(Math.max(1, Math.min(200, limit)), sessionId);
    }

    /** Admin-only audit trail of privileged actions (newest first); filter by user/target. */    /** Admin-only audit trail of privileged actions (newest first); filter by user/target. */
    @GetMapping("/audit")
    public List<AuditLog.Entry> audit(@RequestParam(name = "user", defaultValue = "") String user,
                                      @RequestParam(name = "action", defaultValue = "") String action,
                                      @RequestParam(name = "target", defaultValue = "") String target,
                                      @RequestParam(name = "offset", defaultValue = "0") int offset,
                                      @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return audit.recent(user, action, target, offset, limit);
    }

    /** Download the audit trail as CSV or JSON, with optional filters and a [since, until] window (ms). */
    @GetMapping("/audit/export")
    public ResponseEntity<String> auditExport(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            @RequestParam(name = "user", defaultValue = "") String user,
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "target", defaultValue = "") String target,
            @RequestParam(name = "since", defaultValue = "0") long since,
            @RequestParam(name = "until", defaultValue = "0") long until,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        List<AuditLog.Entry> rows = audit.range(user, action, target, since, until, limit);
        boolean json = "json".equalsIgnoreCase(format);
        String body;
        try {
            body = json ? mapper.writeValueAsString(rows) : AuditLog.toCsv(rows);
        } catch (Exception e) {
            body = json ? "[]" : AuditLog.toCsv(rows);
        }
        String filename = "audit." + (json ? "json" : "csv");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .contentType(json ? MediaType.APPLICATION_JSON : MediaType.valueOf("text/csv"))
                .body(body);
    }

    /** Session-scoped activity (events whose target is this session) -- readable by anyone with access. */
    @GetMapping("/session/activity")
    public Map<String, Object> sessionActivity(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        requireRead(sessionId);
        String tag = "session:" + sessionId;
        List<AuditLog.Entry> hits = audit.recent(null, null, tag, 0, 1000);
        List<AuditLog.Entry> exact = new java.util.ArrayList<>();
        for (AuditLog.Entry e : hits) {
            if (tag.equals(e.target())) exact.add(e); // avoid prefix collisions (session:s1 vs session:s12)
        }
        int from = Math.max(0, offset);
        List<AuditLog.Entry> page = new java.util.ArrayList<>();
        for (int i = from; i < exact.size() && page.size() < Math.max(1, limit); i++) page.add(exact.get(i));
        return Map.of("sessionId", sessionId, "offset", from, "entries", page);
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

    // ---- session export / import ------------------------------------------

    /** Export a session as a portable bundle (conversation + plan history + todos). */
    @GetMapping("/session/export")
    public Map<String, Object> exportSession(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        List<Map<String, Object>> plans = new java.util.ArrayList<>();
        for (Map<String, Object> sum : history.list(sessionId)) {
            Object seq = sum.get("seq");
            if (seq instanceof Number n) {
                Map<String, Object> full = history.get(sessionId, n.intValue());
                if (full != null) plans.add(full);
            }
        }
        Map<String, Object> bundle = SessionBundle.build(sessionId, sessions.owner(sessionId),
                System.currentTimeMillis(), sessions.get(sessionId), plans, todos.get(sessionId),
                skills.sessionOverridesFor(sessionId),
                new java.util.ArrayList<>(sessions.readers(sessionId)));
        try {
            bundle.put("integrity", SkillManifest.sha256(mapper.writeValueAsString(SessionBundle.contentForHash(bundle))));
        } catch (Exception ignore) {
            // integrity is best-effort
        }
        return bundle;
    }

    /** Import a bundle into a NEW session owned by the caller; returns the new session id. */
    /** Project what an import would do (counts before/incoming/after) without applying it. */
    /** Summary of installable content currently in the workspace (skills/agents/commands counts). */
    @GetMapping("/plugin")
    public Map<String, Object> pluginSummary() {
        requireRead("default");
        return plugins.summary();
    }

    /** Export the workspace's skills + agents + commands as a downloadable plugin pack (JSON). */
    @GetMapping("/plugin/export")
    public ResponseEntity<String> exportPlugin(
            @RequestParam(name = "name", defaultValue = "workspace-pack") String name,
            @RequestParam(name = "version", defaultValue = "1") String version,
            @RequestParam(name = "description", defaultValue = "") String description) {
        requireRead("default");
        String body;
        try {
            body = plugins.exportJson(name, version, description);
        } catch (Exception e) {
            body = "{\"error\":\"export failed\"}";
        }
        audit.record(currentUser(), "plugin-export", name, "exported");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + name + ".imini-plugin.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** Browse a plugin registry index: list the packs it advertises (read-only). Uses the default URL if none given. */
    @GetMapping("/plugin/registry")
    public Map<String, Object> pluginRegistry(@RequestParam(name = "url", defaultValue = "") String url) {
        requireRead("default");
        return plugins.fetchRegistry(url);
    }

    /** Install a pack by name from a registry index, pinned to the registry's declared SHA-256 (admin). */
    @PostMapping("/plugin/registry/install")
    public Map<String, Object> installFromRegistry(
            @RequestParam(name = "url", defaultValue = "") String url,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
        requireAdmin();
        Map<String, Object> r = plugins.installFromRegistry(url, name, overwrite);
        audit.record(currentUser(), "plugin-registry-install", name,
                String.valueOf(r.getOrDefault("verification", r.getOrDefault("error", ""))));
        return r;
    }

    /** Export the whole workspace (skills + agents + commands + settings) as one downloadable bundle. */
    @GetMapping("/workspace/export")
    public ResponseEntity<String> workspaceExport(
            @RequestParam(name = "name", defaultValue = "workspace") String name,
            @RequestParam(name = "description", defaultValue = "") String description) throws Exception {
        requireAdmin();
        String json = workspace.exportJson(name, description);
        audit.record(currentUser(), "workspace-export", name, "ok");
        String filename = (name == null || name.isBlank() ? "workspace" : name) + ".imini-workspace.json";
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(json);
    }

    /** A summary of what the workspace would export (skill/agent/command/settings counts). */
    @GetMapping("/workspace/summary")
    public Map<String, Object> workspaceSummary() {
        requireAdmin();
        return workspace.summary();
    }

    /** Inspect the verifier keyring: trusted keys, expiry, revoked/expired/signer flags (admin, read-only). */
    @GetMapping("/workspace/keys")
    public Map<String, Object> workspaceKeys() {
        requireAdmin();
        return workspace.keysInfo();
    }

    /** Mint a fresh Ed25519 key pair (base64) for bundle signing. Keep the private key secret (admin). */
    @PostMapping("/workspace/keygen")
    public Map<String, String> workspaceKeygen() {
        requireAdmin();
        audit.record(currentUser(), "workspace-keygen", "ed25519", "ok");
        return workspace.generateKeyPair();
    }

    /** Dry-run a workspace import: report what would be created/overwritten/changed, writing nothing (admin). */
    @PostMapping("/workspace/import/preview")
    public Map<String, Object> workspaceImportPreview(@RequestBody String bundleJson) {
        requireAdmin();
        return workspace.previewBundle(bundleJson);
    }

    /** Import a whole-workspace bundle: install its pack and apply its settings (admin). */
    @PostMapping("/workspace/import")
    public Map<String, Object> workspaceImport(
            @RequestBody String bundleJson,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
        requireAdmin();
        Map<String, Object> r = workspace.importBundle(bundleJson, overwrite);
        audit.record(currentUser(), "workspace-import", "bundle",
                String.valueOf(r.getOrDefault("error", "ok")));
        return r;
    }

    /**
     * Build a registry index entry for the current workspace's pack: {name, version, description, url,
     * sha256}. Host the exported pack at {@code url}, then paste this entry into your registry index so
     * others can discover and verify it. Admin (it reads workspace content).
     */
    @PostMapping("/plugin/registry/entry")
    public Map<String, Object> registryEntry(
            @RequestParam(name = "name", defaultValue = "workspace-pack") String name,
            @RequestParam(name = "version", defaultValue = "1") String version,
            @RequestParam(name = "description", defaultValue = "") String description,
            @RequestParam(name = "url") String url) {
        requireAdmin();
        Map<String, Object> r = plugins.registryEntry(name, version, description, url);
        audit.record(currentUser(), "plugin-registry-entry", name, String.valueOf(r.getOrDefault("sha256", "")));
        return r;
    }

    /** Sign a registry index JSON (embed a signature over its canonical listing digest). Admin. */
    @PostMapping("/plugin/registry/sign")
    public Map<String, Object> signRegistry(@RequestBody String indexJson) {
        requireAdmin();
        try {
            String signed = plugins.signRegistryIndex(indexJson);
            audit.record(currentUser(), "plugin-registry-sign", "index", "ok");
            return Map.of("signedIndex", signed);
        } catch (Exception e) {
            return Map.of("error", "could not sign index: " + e.getMessage());
        }
    }

    /** Install a plugin pack from a URL, verifying its SHA-256 first (admin). Mirrors remote-skill install. */
    @PostMapping("/plugin/install-url")
    public Map<String, Object> installPluginUrl(
            @RequestParam(name = "url") String url,
            @RequestParam(name = "sha256", defaultValue = "") String sha256,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
        requireAdmin();
        Map<String, Object> r = plugins.installFromUrl(url, sha256, overwrite);
        audit.record(currentUser(), "plugin-install-url", url,
                String.valueOf(r.getOrDefault("verification", r.getOrDefault("error", ""))));
        return r;
    }

    /** Install a plugin pack (JSON body) into the workspace. Admin only (it writes files). */
    @PostMapping("/plugin/install")
    public Map<String, Object> installPlugin(@RequestBody String packJson,
            @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite) {
        requireAdmin();
        Map<String, Object> r = plugins.install(packJson, overwrite);
        audit.record(currentUser(), "plugin-install", String.valueOf(r.getOrDefault("pack", "?")),
                String.valueOf(r.getOrDefault("summary", "")));
        return r;
    }

    /** List scheduled local tasks (id, prompt, kind, timing, status). */    /** List scheduled local tasks (id, prompt, kind, timing, status). */
    @GetMapping("/schedule")
    public List<Map<String, Object>> listSchedule() {
        Principal caller = RequestContext.current();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (ScheduledTasks.Task t : schedule.list()) {
            if (!Ownership.canRead(caller, sessions.owner(t.sessionId), sessions.readers(t.sessionId))) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", t.id);
            m.put("sessionId", t.sessionId);
            m.put("prompt", t.prompt);
            m.put("kind", t.kind);
            m.put("oneShot", t.oneShot);
            m.put("intervalSeconds", t.intervalSeconds);
            m.put("enabled", t.enabled);
            m.put("nextRunEpochMs", t.nextRunEpochMs);
            m.put("lastRunEpochMs", t.lastRunEpochMs);
            m.put("runs", t.runs);
            m.put("lastDetail", t.lastDetail);
            out.add(m);
        }
        return out;
    }

    /** Recent executions of one scheduled task (newest first): status, latency, when. Session read access. */
    @GetMapping("/schedule/runs")
    public List<Map<String, Object>> scheduleRuns(@RequestParam(name = "id") String id,
                                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        Principal caller = RequestContext.current();
        for (ScheduledTasks.Task t : schedule.list()) {
            if (!t.id.equals(id)) continue;
            if (!Ownership.canRead(caller, sessions.owner(t.sessionId), sessions.readers(t.sessionId))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no access to this task's session");
            }
            return schedule.runHistory(id, Math.max(1, Math.min(50, limit)));
        }
        return List.of();
    }

    /** Schedule a task: run a prompt after delaySeconds, optionally repeating every intervalSeconds. */
    @PostMapping("/schedule")
    public Map<String, Object> addSchedule(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "prompt") String prompt,
            @RequestParam(name = "kind", defaultValue = "run") String kind,
            @RequestParam(name = "delaySeconds", defaultValue = "30") long delaySeconds,
            @RequestParam(name = "intervalSeconds", defaultValue = "0") long intervalSeconds,
            @RequestParam(name = "repeat", defaultValue = "false") boolean repeat) {
        requireAccess(sessionId);
        if (prompt == null || prompt.isBlank()) return Map.of("error", "prompt is required");
        ScheduledTasks.Task t = schedule.add(sessionId, prompt, kind, delaySeconds,
                repeat ? intervalSeconds : 0, !repeat, currentUser());
        audit.record(currentUser(), "schedule-add", "session:" + sessionId, t.id + " kind=" + t.kind + " repeat=" + repeat);
        return Map.of("id", t.id, "kind", t.kind, "repeat", repeat, "nextRunEpochMs", t.nextRunEpochMs);
    }

    /** Cancel (remove) a scheduled task. */
    @PostMapping("/schedule/cancel")
    public Map<String, Object> cancelSchedule(@RequestParam(name = "id") String id) {
        boolean ok = schedule.cancel(id);
        if (ok) audit.record(currentUser(), "schedule-cancel", "task:" + id, "cancelled");
        return Map.of("cancelled", ok);
    }

    /** Enable/disable a scheduled task without removing it. */
    @PostMapping("/schedule/toggle")
    public Map<String, Object> toggleSchedule(@RequestParam(name = "id") String id,
                                              @RequestParam(name = "enabled") boolean enabled) {
        boolean ok = schedule.setEnabled(id, enabled);
        return Map.of("ok", ok, "enabled", enabled);
    }

    /** Current token budget for llama-server calls (configured budget, enforced prompt cap, server n_ctx). */
    @GetMapping("/settings/token-budget")
    public Map<String, Object> getTokenBudget() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("budget", tokenBudget.budget());
        out.put("reservedResponse", tokenBudget.reservedResponse());
        int ctx = llama.serverContext();
        out.put("serverContext", ctx);          // 0 = unknown
        out.put("promptCap", tokenBudget.promptCap(ctx));
        out.put("min", TokenBudgetService.MIN_BUDGET);
        return out;
    }

    /** Set the token budget at runtime (admin). Floored at the minimum; returns the effective settings. */
    @PostMapping("/settings/token-budget")
    public Map<String, Object> setTokenBudget(@RequestParam(name = "tokens") int tokens) {
        requireAdmin();
        int set = tokenBudget.setBudget(tokens);
        audit.record(currentUser(), "settings", "token-budget", "set=" + set);
        return getTokenBudget();
    }

    /** The durable cross-session memory note for the current user (carried into new sessions). */
    @GetMapping("/memory/durable")
    public Map<String, Object> durableMemory() {
        String owner = currentUser();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String note = memory.get(owner);
        out.put("owner", owner);
        out.put("note", note == null ? "" : note);
        out.put("present", note != null && !note.isBlank());
        out.put("updatedAt", memory.updatedAt(owner));
        return out;
    }

    /** Clear the current user's durable memory note (admin). */
    @PostMapping("/memory/durable/clear")
    public Map<String, Object> clearDurableMemory() {
        requireAdmin();
        memory.clear(currentUser());
        audit.record(currentUser(), "memory", "durable", "cleared");
        return Map.of("ok", true);
    }

    /**
     * Context-budget pre-flight: estimate the prompt size for a prospective message in a session and
     * predict which context-management actions would fire (compact / trim), before actually sending it.
     */
    @GetMapping("/budget/preflight")
    public Map<String, Object> budgetPreflight(@RequestParam(name = "sessionId", required = false) String sessionId,
                                               @RequestParam(name = "prompt", required = false) String prompt) {
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        List<Map<String, Object>> existing = sessionId == null ? null : sessions.get(sessionId);
        if (existing != null) messages.addAll(existing);
        if (prompt != null && !prompt.isBlank()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("role", "user");
            m.put("content", prompt);
            messages.add(m);
        }
        int estimated = TokenBudget.estimateMessages(messages);
        int ctx = llama.serverContext();
        int cap = tokenBudget.promptCap(ctx);
        int compactThreshold = context.compactThreshold();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("estimatedTokens", estimated);
        out.put("messageCount", messages.size());
        out.put("promptCap", cap);
        out.put("serverContext", ctx);              // 0 = unknown
        out.put("tokenBudget", tokenBudget.budget());
        out.put("compactThreshold", compactThreshold);
        out.put("wouldCompact", estimated >= compactThreshold);
        out.put("wouldTrim", estimated > cap);
        return out;
    }

    /** A session's durable settings (e.g. its default mode). Readable by anyone who can read the session. */
    @GetMapping("/session/settings")
    public Map<String, Object> getSessionSettings(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("settings", sessionSettings.all(sessionId));
        out.put("keys", SessionSettingsResolver.KEYS);
        return out;
    }

    /** Set a durable per-session setting (validated/normalized). Owner/admin/unowned only. */
    @PostMapping("/session/settings")
    public Map<String, Object> setSessionSetting(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "key") String key,
            @RequestParam(name = "value") String value) {
        requireAccess(sessionId);
        if (!SessionSettingsResolver.isValidKey(key)) {
            return Map.of("error", "unknown setting: " + key + " (allowed: " + SessionSettingsResolver.KEYS + ")");
        }
        String norm = SessionSettingsResolver.normalizeValue(key, value);
        if (norm == null) return Map.of("error", "invalid value for " + key + ": " + value);
        sessionSettings.set(sessionId, key, norm);
        audit.record(currentUser(), "session-setting", "session:" + sessionId, key + "=" + norm);
        return Map.of("sessionId", sessionId, "key", key.trim().toLowerCase(), "value", norm);
    }

    /** Clear a per-session setting (it falls back to the global default). */
    @PostMapping("/session/settings/clear")
    public Map<String, Object> clearSessionSetting(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "key") String key) {
        requireAccess(sessionId);
        sessionSettings.clear(sessionId, key);
        return Map.of("sessionId", sessionId, "cleared", key.trim().toLowerCase());
    }

    /** Friendly titles for the readable sessions (id -> title); powers the session list labels. */    /** Friendly titles for the readable sessions (id -> title); powers the session list labels. */
    @GetMapping("/session/titles")
    public Map<String, String> sessionTitles() {
        Principal caller = RequestContext.current();
        List<String> ids = sessions.list().stream()
                .filter(id -> Ownership.canRead(caller, sessions.owner(id), sessions.readers(id)))
                .toList();
        return sessions.titlesFor(ids);
    }

    /** Rename a session (set/clear its friendly title). Owner/admin/unowned only. */
    @PostMapping("/session/rename")
    public Map<String, Object> renameSession(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "title", defaultValue = "") String title) {
        requireAccess(sessionId);
        String clean = SessionNaming.cleanTitle(title);
        sessions.setTitle(sessionId, clean);
        audit.record(currentUser(), "rename", "session:" + sessionId, clean.isEmpty() ? "(cleared)" : clean);
        return Map.of("sessionId", sessionId, "title", clean);
    }

    /**
     * Fork a session: copy its conversation, plan history, and todos into a NEW session owned by the
     * caller. The original is untouched. Returns the new id and what was copied.
     */
    @PostMapping("/session/fork")
    public Map<String, Object> forkSession(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "title", defaultValue = "") String title) {
        requireRead(sessionId);
        String newId = "fork-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.claim(newId, currentUser());

        // 1) conversation
        List<Map<String, Object>> messages = sessions.get(sessionId);
        sessions.save(newId, new java.util.ArrayList<>(messages));
        // 2) todos
        todos.set(newId, todos.get(sessionId));
        // 3) plan history (oldest-first so seq numbering is preserved)
        List<Map<String, Object>> plans = new java.util.ArrayList<>();
        for (Map<String, Object> sum : history.list(sessionId)) {
            Object seq = sum.get("seq");
            if (seq instanceof Number n) {
                Map<String, Object> full = history.get(sessionId, n.intValue());
                if (full != null) plans.add(full);
            }
        }
        java.util.Collections.reverse(plans);
        int copiedPlans = 0;
        for (Map<String, Object> plan : plans) {
            try {
                String goal = String.valueOf(plan.getOrDefault("goal", ""));
                String report = String.valueOf(plan.getOrDefault("report", ""));
                List<TodoStore.Item> items = new java.util.ArrayList<>();
                Map<Integer, List<String>> transcript = new java.util.LinkedHashMap<>();
                if (plan.get("steps") instanceof List<?> steps) {
                    int i = 0;
                    for (Object so : steps) {
                        if (so instanceof Map<?, ?> sm) {
                            Object txt = sm.get("text"), stt = sm.get("status");
                            items.add(new TodoStore.Item(txt == null ? "" : String.valueOf(txt),
                                    stt == null ? "pending" : String.valueOf(stt)));
                            if (sm.get("tools") instanceof List<?> tl) {
                                List<String> ts = new java.util.ArrayList<>();
                                for (Object t : tl) ts.add(String.valueOf(t));
                                transcript.put(i, ts);
                            }
                        }
                        i++;
                    }
                }
                if (!items.isEmpty()) { history.archive(newId, goal, items, transcript, report); copiedPlans++; }
            } catch (Exception ignore) {
                // skip a malformed plan; copy the rest
            }
        }
        // title: explicit, else "fork of <source name>"
        String chosen = SessionNaming.cleanTitle(title);
        if (chosen.isEmpty()) chosen = SessionNaming.forkTitle(sessions.title(sessionId), sessionId);
        sessions.setTitle(newId, chosen);

        audit.record(currentUser(), "fork", "session:" + sessionId,
                "-> " + newId + " messages=" + messages.size() + " plans=" + copiedPlans);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("sessionId", newId);
        out.put("from", sessionId);
        out.put("title", chosen);
        out.put("messages", messages.size());
        out.put("plans", copiedPlans);
        out.put("todos", todos.get(newId).size());
        return out;
    }

    @PostMapping("/session/import/preview")
    public Map<String, Object> importPreview(@RequestBody Map<String, Object> bundle,
            @RequestParam(name = "mode", defaultValue = "new") String mode,
            @RequestParam(name = "target", required = false) String target) {
        List<String> problems = SessionBundle.validate(bundle);
        if (!problems.isEmpty()) return Map.of("error", "invalid bundle", "problems", problems);

        String integrityStatus;
        String stored = SessionBundle.integrity(bundle);
        if (stored.isBlank()) {
            integrityStatus = "none";
        } else {
            String recomputed;
            try {
                recomputed = SkillManifest.sha256(mapper.writeValueAsString(SessionBundle.contentForHash(bundle)));
            } catch (Exception e) {
                recomputed = "";
            }
            integrityStatus = stored.equalsIgnoreCase(recomputed) ? "ok" : "mismatch";
        }

        Map<String, Object> migrated = SessionBundle.migrate(bundle);
        boolean supported = SessionBundle.supports(String.valueOf(migrated.get("version")));
        String m = mode == null ? "new" : mode.trim().toLowerCase(java.util.Locale.ROOT);

        int curMsgs = 0, curTodos = 0, curPlans = 0;
        String dest = "(new session)";
        if (target != null && !target.isBlank() && !"new".equals(m)) {
            requireRead(target);
            dest = target.trim();
            curMsgs = sessions.get(dest).size();
            curTodos = todos.get(dest).size();
            curPlans = history.list(dest).size();
        }
        Map<String, Object> preview = SessionBundle.preview(m, curMsgs, curTodos, curPlans,
                SessionBundle.messages(migrated).size(), SessionBundle.todos(migrated).size(),
                SessionBundle.plans(migrated).size());

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("target", dest);
        out.put("integrity", integrityStatus);
        out.put("version", String.valueOf(migrated.get("version")));
        out.put("supported", supported);
        out.put("preview", preview);
        out.put("skillOverrides", SessionBundle.skillOverrides(migrated).size());
        out.put("readers", SessionBundle.readers(migrated).size());
        return out;
    }

    @PostMapping("/session/import")
    public Map<String, Object> importSession(@RequestBody Map<String, Object> bundle,
            @RequestParam(name = "mode", defaultValue = "new") String mode,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "strict", defaultValue = "true") boolean strict,
            @RequestParam(name = "restoreSharing", defaultValue = "false") boolean restoreSharing) {
        List<String> problems = SessionBundle.validate(bundle);
        if (!problems.isEmpty()) return Map.of("error", "invalid bundle", "problems", problems);

        // integrity: verify the stored hash against a recomputed one (over the bundle AS RECEIVED, before
        // any migration), strict => refuse on mismatch
        String warning = null;
        String stored = SessionBundle.integrity(bundle);
        if (!stored.isBlank()) {
            String recomputed;
            try {
                recomputed = SkillManifest.sha256(mapper.writeValueAsString(SessionBundle.contentForHash(bundle)));
            } catch (Exception e) {
                recomputed = "";
            }
            if (!stored.equalsIgnoreCase(recomputed)) {
                if (strict) return Map.of("error", "integrity check failed", "expected", stored, "actual", recomputed);
                warning = "integrity mismatch (imported anyway)";
            }
        } else {
            warning = "no integrity hash in bundle";
        }

        // migrate older/looser bundles into the current shape, THEN gate on the (migrated) version
        bundle = SessionBundle.migrate(bundle);
        if (!SessionBundle.supports(String.valueOf(bundle.get("version")))) {
            return Map.of("error", "unsupported bundle version",
                    "version", String.valueOf(bundle.getOrDefault("version", "")));
        }

        // resolve destination session + mode
        String m = mode == null ? "new" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        String sessionId;
        if (target != null && !target.isBlank() && !"new".equals(m)) {
            sessionId = target.trim();
            requireAccess(sessionId); // owner/admin/unowned for an existing target
            sessions.claim(sessionId, currentUser());
        } else {
            sessionId = "imp-" + UUID.randomUUID().toString().substring(0, 8);
            sessions.claim(sessionId, currentUser());
            m = "new";
        }

        List<Map<String, Object>> messages = SessionBundle.messages(bundle);
        if ("merge".equals(m)) {
            List<Map<String, Object>> combined = new java.util.ArrayList<>(sessions.get(sessionId));
            combined.addAll(messages);
            sessions.save(sessionId, combined);
        } else { // new | replace
            sessions.save(sessionId, messages);
        }
        todos.set(sessionId, SessionBundle.todos(bundle));

        // restore per-session skill overrides onto the destination session
        int overrides = 0;
        for (Map<String, Object> ov : SessionBundle.skillOverrides(bundle)) {
            String sn = String.valueOf(ov.get("name"));
            boolean on = !Boolean.FALSE.equals(ov.get("enabled"));
            if (!sn.isBlank() && skills.setSessionEnabled(sessionId, sn, on)) overrides++;
        }

        // optionally re-grant the bundle's reader list (the caller is the new owner)
        int sharedWith = 0;
        if (restoreSharing) {
            for (String reader : SessionBundle.readers(bundle)) {
                if (reader != null && !reader.isBlank() && !reader.equals(currentUser())) {
                    sessions.share(sessionId, reader);
                    sharedWith++;
                }
            }
        }

        // restore plan history oldest-first so seq numbering is preserved
        List<Map<String, Object>> plans = new java.util.ArrayList<>(SessionBundle.plans(bundle));
        java.util.Collections.reverse(plans);
        int restored = 0;
        for (Map<String, Object> plan : plans) {
            try {
                String goal = String.valueOf(plan.getOrDefault("goal", ""));
                String report = String.valueOf(plan.getOrDefault("report", ""));
                Object stepsObj = plan.get("steps");
                List<TodoStore.Item> items = new java.util.ArrayList<>();
                Map<Integer, List<String>> transcript = new java.util.LinkedHashMap<>();
                if (stepsObj instanceof List<?> steps) {
                    int i = 0;
                    for (Object so : steps) {
                        if (so instanceof Map<?, ?> sm) {
                            Object txt = sm.get("text"), stt = sm.get("status");
                            items.add(new TodoStore.Item(txt == null ? "" : String.valueOf(txt),
                                    stt == null ? "pending" : String.valueOf(stt)));
                            Object tools = sm.get("tools");
                            if (tools instanceof List<?> tl) {
                                List<String> ts = new java.util.ArrayList<>();
                                for (Object t : tl) ts.add(String.valueOf(t));
                                transcript.put(i, ts);
                            }
                        }
                        i++;
                    }
                }
                if (!items.isEmpty()) { history.archive(sessionId, goal, items, transcript, report); restored++; }
            } catch (Exception ignore) {
                // skip a malformed plan; import the rest
            }
        }
        audit.record(currentUser(), "import", "session:" + sessionId,
                "mode=" + m + " messages=" + messages.size() + " plans=" + restored);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("mode", m);
        out.put("messages", messages.size());
        out.put("plans", restored);
        out.put("todos", SessionBundle.todos(bundle).size());
        out.put("skillOverrides", overrides);
        out.put("sharedWith", sharedWith);
        if (warning != null) out.put("warning", warning);
        return out;
    }

    // ---- skills management -------------------------------------------------

    /** List loaded skills (name, description, enabled) for the UI. Open to any authenticated user. */
    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestParam(name = "sessionId", required = false) String sessionId) {
        List<Map<String, Object>> list = skills.listForUi(sessionId);
        return Map.of("skills", list, "count", list.size());
    }

    /** Set a per-session skill override (enable/disable for this session only). */
    @PostMapping("/skills/session-toggle")
    public Map<String, Object> sessionToggleSkill(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        requireAccess(sessionId);
        String name = String.valueOf(body.getOrDefault("name", ""));
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled"));
        boolean found = skills.setSessionEnabled(sessionId, name, enabled);
        audit.record(currentUser(), "skill-session-toggle", "skill:" + name,
                sessionId + " " + (enabled ? "enabled" : "disabled"));
        return Map.of("name", name, "enabled", enabled, "found", found, "skills", skills.listForUi(sessionId));
    }

    /** Clear a per-session override (revert this skill to the global default for this session). */
    @PostMapping("/skills/session-reset")
    public Map<String, Object> sessionResetSkill(@RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        requireAccess(sessionId);
        String name = String.valueOf(body.getOrDefault("name", ""));
        skills.clearSessionEnabled(sessionId, name);
        audit.record(currentUser(), "skill-session-reset", "skill:" + name, sessionId);
        return Map.of("name", name, "skills", skills.listForUi(sessionId));
    }

    /** Enable/disable a skill (admin only; skills are a global resource). */
    @PostMapping("/skills/toggle")
    public Map<String, Object> toggleSkill(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String name = String.valueOf(body.getOrDefault("name", ""));
        boolean enabled = !Boolean.FALSE.equals(body.get("enabled")); // default to enable
        boolean found = skills.setEnabled(name, enabled);
        audit.record(currentUser(), "skill-toggle", "skill:" + name, enabled ? "enabled" : "disabled");
        return Map.of("name", name, "enabled", enabled, "found", found, "skills", skills.listForUi());
    }

    /** Re-pull the configured remote skill repositories and reload (admin only). */
    @PostMapping("/skills/refresh")
    public Map<String, Object> refreshSkills() {
        requireAdmin();
        String msg = skills.refresh();
        audit.record(currentUser(), "skill-refresh", "skills", msg);
        return Map.of("message", msg, "skills", skills.listForUi());
    }

    /** A member proposes a skill ({name, description, body}); it is queued for admin review. */
    @PostMapping("/skills/request")
    public Map<String, Object> requestSkill(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String desc = String.valueOf(body.getOrDefault("description", "")).trim();
        String text = String.valueOf(body.getOrDefault("body", "")).trim();
        if (name.isEmpty() || text.isEmpty()) {
            return Map.of("error", "name and body are required");
        }
        String id = skillRequests.submit(currentUser(), name, desc, text);
        audit.record(currentUser(), "skill-request", "skill:" + name, "queued " + id);
        return Map.of("id", id, "status", "pending");
    }

    /** Pending skill proposals (admin only). */
    @GetMapping("/skills/requests")
    public Map<String, Object> skillRequests(
            @RequestParam(name = "status", defaultValue = "pending") String status) {
        requireAdmin();
        return Map.of("requests", skillRequests.list(status));
    }

    /** Approve (save the skill) or reject a proposal (admin only). */
    @PostMapping("/skills/requests/resolve")
    public Map<String, Object> resolveSkillRequest(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String id = String.valueOf(body.getOrDefault("id", ""));
        boolean approve = Boolean.TRUE.equals(body.get("approve"));
        Map<String, Object> req = skillRequests.get(id);
        if (req == null) return Map.of("error", "request not found", "id", id);

        String result;
        if (approve) {
            result = skills.saveApproved(String.valueOf(req.get("name")),
                    String.valueOf(req.get("description")), String.valueOf(req.get("body")));
            skillRequests.setStatus(id, "approved");
        } else {
            skillRequests.setStatus(id, "rejected");
            result = "rejected";
        }
        audit.record(currentUser(), "skill-request-resolve", "skill:" + req.get("name"),
                (approve ? "approved " : "rejected ") + id);
        return Map.of("id", id, "status", approve ? "approved" : "rejected", "result", result,
                "requests", skillRequests.list("pending"));
    }

    /** The caller's own skill proposals and their status. */
    @GetMapping("/skills/requests/mine")
    public Map<String, Object> myskillRequests() {
        return Map.of("requests", skillRequests.listByRequester(currentUser()));
    }

    /** Withdraw one of the caller's own pending proposals. */
    @PostMapping("/skills/requests/withdraw")
    public Map<String, Object> withdrawSkillRequest(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.getOrDefault("id", ""));
        Map<String, Object> req = skillRequests.get(id);
        if (req == null) return Map.of("error", "request not found", "id", id);
        if (!currentUser().equals(req.get("requester"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your request");
        }
        if (!"pending".equals(req.get("status"))) return Map.of("error", "only pending requests can be withdrawn");
        skillRequests.setStatus(id, "withdrawn");
        return Map.of("id", id, "status", "withdrawn", "requests", skillRequests.listByRequester(currentUser()));
    }

    /** Edit one of the caller's own pending proposals. */
    @PostMapping("/skills/requests/update")
    public Map<String, Object> updateSkillRequest(@RequestBody Map<String, Object> body) {
        String id = String.valueOf(body.getOrDefault("id", ""));
        Map<String, Object> req = skillRequests.get(id);
        if (req == null) return Map.of("error", "request not found", "id", id);
        if (!currentUser().equals(req.get("requester"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not your request");
        }
        if (!"pending".equals(req.get("status"))) return Map.of("error", "only pending requests can be edited");
        String name = String.valueOf(body.getOrDefault("name", req.get("name"))).trim();
        String desc = String.valueOf(body.getOrDefault("description", req.get("description"))).trim();
        String text = String.valueOf(body.getOrDefault("body", req.get("body"))).trim();
        if (name.isEmpty() || text.isEmpty()) return Map.of("error", "name and body are required");
        skillRequests.update(id, name, desc, text);
        return Map.of("id", id, "status", "pending", "requests", skillRequests.listByRequester(currentUser()));
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

    /** Project-memory diagnostics: which CLAUDE.md-style files loaded (and imports), in order. */
    @GetMapping("/memory/files")
    public Map<String, Object> memoryFiles() {
        List<MemoryLoader.Source> sources = project.diagnostics();
        List<Map<String, Object>> files = new java.util.ArrayList<>();
        int totalBytes = 0;
        for (MemoryLoader.Source s : sources) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("path", s.path());
            m.put("reason", s.reason());
            m.put("bytes", s.bytes());
            m.put("depth", s.depth());
            files.add(m);
            if (!s.reason().startsWith("skipped")) totalBytes += s.bytes();
        }
        return Map.of("files", files, "count", files.size(), "totalBytes", totalBytes, "report", project.report());
    }

    /** Scan the repo and draft CLAUDE.md. write=true creates it (or replaces, with overwrite=true). */
    @PostMapping("/init")
    public Map<String, Object> init(@RequestParam(name = "write", defaultValue = "false") boolean write,
                                    @RequestParam(name = "overwrite", defaultValue = "false") boolean overwrite,
                                    @RequestParam(name = "augment", defaultValue = "false") boolean augment) {
        Map<String, Object> r = init.initInfo(write, overwrite, augment);
        if (Boolean.TRUE.equals(r.get("wrote"))) {
            audit.record(currentUser(), "init", "CLAUDE.md", String.valueOf(r.get("message")));
        }
        return r;
    }

    /** Staged (not-yet-applied) patch previews for a session, for the browser diff viewer. */
    @GetMapping("/preview")
    public List<Map<String, Object>> previews(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        requireRead(sessionId);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (PreviewStore.Preview p : previews.listFor(sessionId)) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", p.id());
            m.put("summary", p.summary());
            m.put("diff", p.diff());
            m.put("ts", p.ts());
            List<Map<String, Object>> hunks = new java.util.ArrayList<>();
            for (PreviewStore.Hunk h : p.hunks()) {
                Map<String, Object> hm = new java.util.LinkedHashMap<>();
                hm.put("index", h.index());
                hm.put("path", h.path());
                hm.put("kind", h.kind());
                hm.put("added", h.added());
                hm.put("removed", h.removed());
                hm.put("diff", h.diff());
                hunks.add(hm);
            }
            m.put("hunks", hunks);
            out.add(m);
        }
        return out;
    }

    /** Apply a staged preview (re-validates + snapshots). */
    @PostMapping("/preview/apply")
    public Map<String, Object> applyPreview(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
                                            @RequestParam(name = "id", defaultValue = "") String id,
                                            @RequestParam(name = "hunks", defaultValue = "") String hunks) {
        requireAccess(sessionId);
        String result = builtins.applyPreview(sessionId, id, hunks);
        audit.record(currentUser(), "preview-apply", "session:" + sessionId, result);
        return Map.of("result", result);
    }

    /** Discard a staged preview without applying it. */
    @PostMapping("/preview/discard")
    public Map<String, Object> discardPreview(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
                                              @RequestParam(name = "id", defaultValue = "") String id,
                                              @RequestParam(name = "hunks", defaultValue = "") String hunks) {
        requireAccess(sessionId);
        String result = builtins.discardPreview(sessionId, id, hunks);
        return Map.of("result", result);
    }

    // ---- helpers -----------------------------------------------------------

    private String resolveSession(String s) {
        return (s == null || s.isBlank()) ? UUID.randomUUID().toString().substring(0, 8) : s;
    }

    private static String currentUser() {
        return RequestContext.current().user();
    }

    /** 403 unless the caller is an admin. */
    private void requireAdmin() {
        Principal p = RequestContext.current();
        if (p == null || !p.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        }
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

    /** Mode for a turn: explicit request value, else the session's stored default, else global ASK. */
    private Mode effectiveMode(String sessionId, String requestMode) {
        String sessionMode = sessionSettings.get(sessionId, "mode");
        return parseMode(SessionSettingsResolver.resolveMode(requestMode, sessionMode, "ask"));
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
