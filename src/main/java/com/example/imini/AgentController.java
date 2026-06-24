package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final Database db;
    private final SessionReaper reaper;
    private final Tracer tracer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private WebSearchService webSearch;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private McpManager mcp;
    private final CostService cost;
    private final EvalHarness eval;
    private final CapabilityService capabilities;
    private final WorkspaceRoots workspaceRoots;
    private final ToolRateLimiter toolRateLimiter;
    private final AlertSink alertSink;
    private final CsrfGuard csrf;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints,
                           TodoStore todos, InterruptService interrupt, RunService runService,
                           RetrievalService retrieval, Metrics metrics, Approvals approvals,
                           AuditLog audit, PlanStore plans, RunRecorder recorder, PlanHistory history,
                           SkillService skills, SkillRequests skillRequests, ProjectContext project, InitService init,
                           PreviewStore previews, BuiltinTools builtins,
                           TokenBudgetService tokenBudget, LlamaClient llama, ScheduledTasks schedule,
                           PluginService plugins, SessionSettings sessionSettings,
                           WorkspaceService workspace, MemoryStore memory, ContextManager context,
                           Database db, SessionReaper reaper,
                           Tracer tracer, CostService cost, EvalHarness eval, CapabilityService capabilities,
                           ToolRateLimiter toolRateLimiter, AlertSink alertSink, CsrfGuard csrf,
                           WorkspaceRoots workspaceRoots) {
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
        this.db = db;
        this.reaper = reaper;
        this.tracer = tracer;
        this.cost = cost;
        this.eval = eval;
        this.capabilities = capabilities;
        this.toolRateLimiter = toolRateLimiter;
        this.alertSink = alertSink;
        this.csrf = csrf;
        this.workspaceRoots = workspaceRoots;
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
        final String tenant = currentUser();
        // Per-tenant quota: deny before doing any work if the tenant is over its monthly token budget.
        if (!cost.allow(tenant)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "monthly token quota exceeded for " + tenant);
        }
        sessions.claim(sessionId, tenant);
        audit.record(tenant, planAction("ask", plan, resume), "session:" + sessionId, "started");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        Tracer.Span span = tracer.startWithContext("ask", RequestContext.traceparent())
                .attr("session", sessionId).attr("tenant", tenant)
                .attr("mode", mode.name().toLowerCase());
        try {
            int inTokens = llama.countTokens(q);
            String answer = runService.runBounded(() ->
                    (image != null && !image.isBlank()) ? loop.run(sessionId, q, image, imageType, mode, new ConsoleSink())
                         : (plan && resume) ? loop.resumePlan(sessionId, mode, new ConsoleSink())
                         : plan ? loop.runPlan(sessionId, q, mode, new ConsoleSink())
                         : loop.run(sessionId, q, mode, new ConsoleSink()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            int outTokens = answer == null ? 0 : Math.max(0, answer.length() / 4); // ~4 chars/token
            long micro = cost.record(tenant, "/ask", sessionId, Math.max(0, inTokens), outTokens);
            span.attr("input_tokens", Math.max(0, inTokens)).attr("output_tokens", outTokens)
                .attr("micro_usd", micro).attr("latency_ms", ms);
            metrics.recordRun("/ask", sessionId, mode.name().toLowerCase(), ms, true);
            metrics.logRun("/ask", sessionId, null, ms, true);
            return Map.of("answer", answer);
        } catch (Exception e) {
            span.error(e.getMessage());
            metrics.recordRun("/ask", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        } finally {
            span.end();
        }
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        final String tenant = currentUser();
        if (!cost.allow(tenant)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "monthly token quota exceeded for " + tenant);
        }
        sessions.claim(sessionId, tenant);
        final Mode mode = effectiveMode(sessionId, body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        audit.record(tenant, planAction("chat", plan, resume), "session:" + sessionId, "started");
        final String message = body.getOrDefault("message", "");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        Tracer.Span span = tracer.startWithContext("chat", RequestContext.traceparent())
                .attr("session", sessionId).attr("tenant", tenant).attr("mode", mode.name().toLowerCase());
        try {
            int inTokens = llama.countTokens(message);
            String answer = runService.runBounded(() ->
                    (plan && resume) ? loop.resumePlan(sessionId, mode, new ConsoleSink())
                         : plan ? loop.runPlan(sessionId, message, mode, new ConsoleSink())
                         : loop.chat(sessionId, message, mode, new ConsoleSink()));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            int outTokens = answer == null ? 0 : Math.max(0, answer.length() / 4);
            long micro = cost.record(tenant, "/chat", sessionId, Math.max(0, inTokens), outTokens);
            span.attr("input_tokens", Math.max(0, inTokens)).attr("output_tokens", outTokens)
                .attr("micro_usd", micro).attr("latency_ms", ms);
            metrics.recordRun("/chat", sessionId, mode.name().toLowerCase(), ms, true);
            metrics.logRun("/chat", sessionId, null, ms, true);
            return Map.of("sessionId", sessionId, "answer", answer);
        } catch (Exception e) {
            span.error(e.getMessage());
            metrics.recordRun("/chat", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
            throw e;
        } finally {
            span.end();
        }
    }

    // ---- streaming (SSE) ---------------------------------------------------

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        final String sessionId = resolveSession(body.get("sessionId"));
        requireAccess(sessionId);
        final String tenant = currentUser();
        if (!cost.allow(tenant)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "monthly token quota exceeded for " + tenant);
        }
        sessions.claim(sessionId, tenant);
        final Mode mode = effectiveMode(sessionId, body.get("mode"));
        final boolean plan = isPlan(body.get("plan"));
        final boolean resume = isPlan(body.get("resume"));
        audit.record(tenant, planAction("chat/stream", plan, resume), "session:" + sessionId, "started");
        final String message = body.getOrDefault("message", "");
        final String traceparent = RequestContext.traceparent(); // capture before leaving the request thread
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            Tracer.Span span = tracer.startWithContext("chat/stream", traceparent)
                    .attr("session", sessionId).attr("tenant", tenant).attr("mode", mode.name().toLowerCase());
            try {
                send(emitter, "session", sessionId);
                int inTokens = llama.countTokens(message);
                String answer = runService.runBounded(() ->
                        (plan && resume) ? loop.resumePlan(sessionId, mode, sink)
                             : plan ? loop.runPlan(sessionId, message, mode, sink)
                             : loop.chat(sessionId, message, mode, sink));
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                int outTokens = answer == null ? 0 : Math.max(0, answer.length() / 4);
                long micro = cost.record(tenant, "/chat/stream", sessionId, Math.max(0, inTokens), outTokens);
                span.attr("input_tokens", Math.max(0, inTokens)).attr("output_tokens", outTokens)
                    .attr("micro_usd", micro).attr("latency_ms", ms);
                metrics.recordRun("/chat/stream", sessionId, mode.name().toLowerCase(), ms, true);
                metrics.logRun("/chat/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                span.error(e.getMessage());
                metrics.recordRun("/chat/stream", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
                send(emitter, "error", String.valueOf(e.getMessage()));
                emitter.complete();
            } finally {
                span.end();
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
        final String tenant = currentUser();
        if (!cost.allow(tenant)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "monthly token quota exceeded for " + tenant);
        }
        sessions.claim(sessionId, tenant);
        audit.record(tenant, planAction("ask/stream", plan, resume), "session:" + sessionId, "started");
        final String traceparent = RequestContext.traceparent(); // capture before leaving the request thread
        SseEmitter emitter = new SseEmitter(0L);
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            Tracer.Span span = tracer.startWithContext("ask/stream", traceparent)
                    .attr("session", sessionId).attr("tenant", tenant).attr("mode", mode.name().toLowerCase());
            try {
                send(emitter, "session", sessionId);
                int inTokens = llama.countTokens(q);
                String answer = runService.runBounded(() ->
                        (plan && resume) ? loop.resumePlan(sessionId, mode, sink)
                             : plan ? loop.runPlan(sessionId, q, mode, sink)
                             : loop.run(sessionId, q, mode, sink));
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                int outTokens = answer == null ? 0 : Math.max(0, answer.length() / 4);
                long micro = cost.record(tenant, "/ask/stream", sessionId, Math.max(0, inTokens), outTokens);
                span.attr("input_tokens", Math.max(0, inTokens)).attr("output_tokens", outTokens)
                    .attr("micro_usd", micro).attr("latency_ms", ms);
                metrics.recordRun("/ask/stream", sessionId, mode.name().toLowerCase(), ms, true);
                metrics.logRun("/ask/stream", sessionId, null, ms, true);
                send(emitter, "answer", answer);
                send(emitter, "done", "");
                emitter.complete();
            } catch (Exception e) {
                span.error(e.getMessage());
                metrics.recordRun("/ask/stream", sessionId, mode.name().toLowerCase(), (System.nanoTime() - t0) / 1_000_000L, false);
                send(emitter, "error", String.valueOf(e.getMessage()));
                emitter.complete();
            } finally {
                span.end();
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

    /** Age/size distribution of stored sessions, plus the configured TTL. Admin only. */
    @GetMapping("/sessions/summary")
    public Map<String, Object> sessionsSummary() {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>(sessions.summary(System.currentTimeMillis()));
        out.put("ttlHours", reaper.ttlMs() / 3_600_000L);
        return out;
    }

    /** Run a session-pruning pass immediately (respects the configured TTL). Returns how many were pruned. Admin only. */
    @PostMapping("/sessions/prune")
    public Map<String, Object> sessionsPrune() {
        requireAdmin();
        int pruned = reaper.reap();
        return Map.of("pruned", pruned, "ttlHours", reaper.ttlMs() / 3_600_000L);
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

    /** Roll a readiness snapshot's component states into an overall status. */
    static String readinessStatus(boolean dbOk, boolean llamaOk) {
        if (dbOk && llamaOk) return "ok";
        if (!dbOk && !llamaOk) return "down";
        return "degraded";
    }

    /**
     * Readiness probe for deployment/monitoring: database availability, llama-server reachability (and its
     * context window), persistence mode, uptime, and a compact observability snapshot (context-management
     * counts + durable-memory presence). Open (no auth) like {@code /health}; returns 200 with a status of
     * "ok", "degraded" (one dependency down), or "down".
     */
    @GetMapping("/healthz")
    @SuppressWarnings("unchecked")
    public Map<String, Object> healthz() {
        boolean dbOk = db.available();
        int ctx = llama.serverContext();         // 0 when the llama-server is unreachable
        boolean llamaOk = ctx > 0;

        Map<String, Object> snap = metrics.snapshot();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", readinessStatus(dbOk, llamaOk));
        out.put("db", Map.of("available", dbOk, "persistent", dbOk));
        out.put("llama", Map.of("reachable", llamaOk, "contextTokens", ctx,
                "circuitBreaker", llama.breakerState().name().toLowerCase()));
        out.put("uptimeMs", snap.getOrDefault("uptime_ms", 0L));
        out.put("context", snap.getOrDefault("context", Map.of())); // folds/compactions/trims
        String durable = memory.get(MemoryStore.DEFAULT_OWNER);
        out.put("memory", Map.of(
                "workspace", MemoryStore.workspaceId(),
                "durablePresent", durable != null && !durable.isBlank()));
        return out;
    }

    @GetMapping("/me")
    public Map<String, String> me() {
        Principal p = RequestContext.current();
        return Map.of("user", p.user(), "role", p.role());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> snap = metrics.snapshot();
        snap.put("alerts", alertSink.stats());
        return snap;
    }

    /** Metrics in Prometheus text exposition format, for external scraping (admin only). */
    @GetMapping("/metrics/prom")
    public ResponseEntity<String> metricsProm() {
        requireAdmin();
        Map<String, Object> snap = metrics.snapshot();
        snap.put("alerts", alertSink.stats());
        return ResponseEntity.ok()
                .header("Content-Type", PromFormat.CONTENT_TYPE)
                .body(PromFormat.render(snap));
    }

    /** Alert deliveries that exhausted their retries (newest first). Admin only. */
    @PostMapping("/admin/mcp/reload")
    public Map<String, Object> adminMcpReload() {
        requireAdmin();
        if (mcp == null) {
            return Map.of("enabled", false);
        }
        return mcp.reload(); // republishes MCP tools via the reload hook set by ToolRegistry
    }

    @GetMapping("/admin/capability-provisioning")
    public Map<String, Object> adminCapabilityProvisioning() {
        requireAdmin();
        Map<String, List<String>> applied = skills == null ? Map.of() : skills.lifecycleLastApplied();
        Object lastReload = (mcp == null) ? null : mcp.diagnostics().get("last_reload");
        @SuppressWarnings("unchecked")
        Map<String, Object> reloadMap = (lastReload instanceof Map) ? (Map<String, Object>) lastReload : null;
        return CapabilityProvisioning.view(applied, reloadMap);
    }

    @GetMapping("/admin/skills/lifecycle")
    public Map<String, Object> adminSkillsLifecycle() {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("bindings", skills.lifecycleBindings());
        out.put("last_applied", skills.lifecycleLastApplied());
        return out;
    }

    @GetMapping("/admin/mcp")
    public Map<String, Object> adminMcp() {
        requireAdmin();
        if (mcp == null) {
            return Map.of("enabled", false);
        }
        return mcp.diagnostics();
    }

    @GetMapping("/admin/web-search")
    public Map<String, Object> adminWebSearch() {
        requireAdmin();
        if (webSearch == null) {
            return Map.of("enabled", false);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", true);
        out.putAll(webSearch.metrics().snapshot());
        return out;
    }

    @GetMapping("/admin/alerts/failed")
    public Map<String, Object> adminAlertsFailed(
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireAdmin();
        String a = action.isBlank() ? null : action;
        String st = status.isBlank() ? null : status;
        String query = q.isBlank() ? null : q;
        int off = Math.max(0, offset);
        int capped = Math.max(1, Math.min(limit, 500));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("stats", alertSink.stats());
        out.put("total", alertSink.deadLetterCount(a, st, query));
        out.put("offset", off);
        out.put("limit", capped);
        out.put("dead_letters", alertSink.deadLetterPage(a, st, query, off, capped));
        return out;
    }

    /** Human-readable dead-letter viewer: a filterable, paginated HTML page with ack/replay/delete. Admin. */
    @GetMapping(value = "/admin/alerts.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> adminAlertsHtml(
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireAdmin();
        String a = action.isBlank() ? null : action;
        String st = status.isBlank() ? null : status;
        String query = q.isBlank() ? null : q;
        int off = Math.max(0, offset);
        int capped = Math.max(1, Math.min(limit, 500));
        var rows = alertSink.deadLetterPage(a, st, query, off, capped);
        int total = alertSink.deadLetterCount(a, st, query);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(DeadLetterDashboard.render(rows, action, status, q, off, capped, total,
                        csrf.token(), alertSink.dedupSummary(20)));
    }

    /** Top currently-throttled dedup keys (most suppressed first). Admin only. */
    @GetMapping("/admin/alerts/digests")
    public Map<String, Object> adminAlertsDigests(
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("digests", alertSink.dedupSummary(limit));
        return out;
    }

    /** A single operator overview of the alerting pipeline (counters, routes, tiers, top suppressed). Admin. */
    @GetMapping(value = "/admin/alerts/overview.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> adminAlertsOverview(
            @RequestParam(name = "refresh", defaultValue = "10") int refresh) {
        requireAdmin();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(AlertsOverview.render(alertSink.stats(), alertSink.dedupSummary(10), Math.max(0, refresh), csrf.token()));
    }

    /** JSON backing the overview page's live auto-refresh. Admin only. */
    /** The current workspace-roots registry (Track B): default root + per-session grants with TTL. Admin only. */
    @GetMapping("/admin/roots")
    public Map<String, Object> adminRoots() {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", workspaceRoots.enabled());
        out.put("default", workspaceRoots.defaultRoot().toString());
        Map<String, java.util.List<Map<String, Object>>> sessions = new java.util.LinkedHashMap<>();
        for (WorkspaceRoots.GrantMeta g : workspaceRoots.allGrants()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", g.id());
            m.put("path", g.path().toString());
            m.put("access", g.access().toString());
            m.put("granted_at", g.grantedAt());
            m.put("remaining_ttl_ms", g.remainingTtlMs()); // null when unlimited
            sessions.computeIfAbsent(g.sessionId(), k -> new java.util.ArrayList<>()).add(m);
        }
        out.put("sessions", sessions);
        return out;
    }

    @GetMapping("/admin/alerts/overview.json")
    public Map<String, Object> adminAlertsOverviewJson() {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("stats", alertSink.stats());
        out.put("digests", alertSink.dedupSummary(10));
        return out;
    }

    /** The effective, resolved alerting configuration (webhooks masked, no secrets). Admin only. */
    @GetMapping("/admin/alerts/config")
    public Map<String, Object> adminAlertsConfig() {
        requireAdmin();
        Map<String, Object> out = alertSink.configSnapshot();
        out.put("csrf", csrf.configSnapshot());
        return out;
    }

    /**
     * Downloadable SLO report: daily good/total per day for the rolling window (global + per route), with
     * effective targets and a pass flag. CSV by default ({@code Content-Disposition: attachment});
     * {@code ?format=json} returns the raw rows. Optional date range: {@code ?from=YYYY-MM-DD&to=YYYY-MM-DD} or
     * {@code ?days=N} (last N days). Admin only.
     */
    @GetMapping("/admin/alerts/slo-report")
    public ResponseEntity<?> adminAlertsSloReport(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            @RequestParam(name = "from", defaultValue = "") String from,
            @RequestParam(name = "to", defaultValue = "") String to,
            @RequestParam(name = "days", defaultValue = "0") int days) {
        requireAdmin();
        long today = System.currentTimeMillis() / 86_400_000L;
        long fromDay = Long.MIN_VALUE, toDay = Long.MAX_VALUE;
        if (days > 0) {
            fromDay = today - days + 1;
        } else {
            if (!from.isBlank()) try { fromDay = java.time.LocalDate.parse(from.trim()).toEpochDay(); } catch (Exception ignore) {}
            if (!to.isBlank()) try { toDay = java.time.LocalDate.parse(to.trim()).toEpochDay(); } catch (Exception ignore) {}
        }
        List<Map<String, Object>> rows = alertSink.sloReportRows(fromDay, toDay);
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rows);
        }
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv")
                .header("Content-Disposition", "attachment; filename=\"imini-slo-report.csv\"")
                .body(AlertSink.sloReportCsv(rows));
    }

    /**
     * Manually send an SLO digest now (instead of waiting for the scheduler), using the configured template and
     * webhook. Useful for testing the digest wiring/format. CSRF-guarded (it performs a live POST). Admin only.
     */
    @PostMapping("/admin/alerts/slo-digest")
    public Map<String, Object> adminAlertsSloDigest(
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        return alertSink.postSloDigest(force);
    }

    /** Recent SLO digests (newest-first), from the persisted history. Admin only. */
    @GetMapping("/admin/alerts/slo-digest/history")
    public ResponseEntity<?> adminAlertsSloDigestHistory(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestParam(name = "from", defaultValue = "") String from,
            @RequestParam(name = "to", defaultValue = "") String to,
            @RequestParam(name = "days", defaultValue = "0") int days) {
        requireAdmin();
        String err = AlertSink.rangeError(from, to, days);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        long[] r = dateRangeMs(from, to, days);
        List<Map<String, Object>> rows = alertSink.sloDigestHistory(Math.max(1, Math.min(200, limit)), r[0], r[1]);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition", "attachment; filename=\"imini-digest-history.csv\"")
                    .body(AlertSink.digestHistoryCsv(rows));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rows);
    }

    /** Resolve from/to (ISO yyyy-MM-dd) or a trailing days window into [fromMs, toMs] epoch-ms bounds. */
    private static long[] dateRangeMs(String from, String to, int days) {
        long fromMs = Long.MIN_VALUE, toMs = Long.MAX_VALUE;
        if (days > 0) {
            fromMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        } else {
            if (!from.isBlank()) try { fromMs = java.time.LocalDate.parse(from.trim()).toEpochDay() * 86_400_000L; } catch (Exception ignore) {}
            if (!to.isBlank()) try { toMs = (java.time.LocalDate.parse(to.trim()).toEpochDay() + 1) * 86_400_000L - 1; } catch (Exception ignore) {}
        }
        return new long[]{fromMs, toMs};
    }

    /** Mute scheduled SLO digests for {@code hours} (so a known-degraded period doesn't keep paging). CSRF. */
    @PostMapping("/admin/alerts/slo-digest/mute")
    public ResponseEntity<Map<String, Object>> adminAlertsSloDigestMute(
            @RequestParam(name = "hours", defaultValue = "4") double hours,
            @RequestParam(name = "reason", defaultValue = "") String reason,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        try {
            long until = alertSink.muteDigest(hours, reason, currentUser());
            return ResponseEntity.ok(Map.of("muted", true, "muted_until", until, "reason", alertSink.digestMuteReason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("muted", false, "error", e.getMessage()));
        }
    }

    /** Recent digest mute/unmute/auto-expire audit events (newest-first), JSON (default) or CSV. Admin only. */
    @GetMapping("/admin/alerts/digest-audit")
    public ResponseEntity<?> adminAlertsDigestAudit(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestParam(name = "from", defaultValue = "") String from,
            @RequestParam(name = "to", defaultValue = "") String to,
            @RequestParam(name = "days", defaultValue = "0") int days) {
        requireAdmin();
        String err = AlertSink.rangeError(from, to, days);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        long[] r = dateRangeMs(from, to, days);
        List<Map<String, Object>> rows = alertSink.digestAuditTrail(Math.max(1, Math.min(200, limit)), r[0], r[1]);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition", "attachment; filename=\"imini-digest-audit.csv\"")
                    .body(AlertSink.digestAuditCsv(rows));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rows);
    }

    /**
     * Combined digest report for a date range: current mute state + digest history + mute/audit trail in one
     * response (JSON default, or a single sectioned CSV with {@code ?format=csv}). Admin only.
     */
    @GetMapping("/admin/alerts/digest-report")
    public ResponseEntity<?> adminAlertsDigestReport(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestParam(name = "from", defaultValue = "") String from,
            @RequestParam(name = "to", defaultValue = "") String to,
            @RequestParam(name = "days", defaultValue = "0") int days) {
        requireAdmin();
        String err = AlertSink.rangeError(from, to, days);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        long[] r = dateRangeMs(from, to, days);
        int lim = Math.max(1, Math.min(200, limit));
        Map<String, Object> mute = alertSink.digestMuteState();
        Map<String, Object> snapshot = alertSink.sloDigest();
        List<Map<String, Object>> history = alertSink.sloDigestHistory(lim, r[0], r[1]);
        List<Map<String, Object>> audit = alertSink.digestAuditTrail(lim, r[0], r[1]);
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv")
                    .header("Content-Disposition", "attachment; filename=\"imini-digest-report.csv\"")
                    .body(AlertSink.digestReportCsv(mute, snapshot, history, audit));
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("snapshot", snapshot);
        out.put("mute", mute);
        out.put("history", history);
        out.put("audit", audit);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(out);
    }

    /** Clear any SLO digest mute. CSRF-guarded. Admin only. */
    @PostMapping("/admin/alerts/slo-digest/unmute")
    public Map<String, Object> adminAlertsSloDigestUnmute(
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        alertSink.unmuteDigest(currentUser());
        return Map.of("muted", false);
    }

    /**
     * Hot-reload the alerting config without a restart. Pass any of {@code actions}, {@code routes},
     * {@code escalate-tiers}, {@code slo-latency-ms}, {@code slo-target}; omitted pieces are left unchanged.
     * Re-parses into the live sink and returns the resulting config (with fresh warnings). CSRF-guarded.
     * Admin only.
     */
    @PostMapping("/admin/alerts/reload")
    public Map<String, Object> adminAlertsReload(
            @RequestParam(name = "actions", required = false) String actions,
            @RequestParam(name = "routes", required = false) String routes,
            @RequestParam(name = "escalate-tiers", required = false) String escalateTiers,
            @RequestParam(name = "slo-latency-ms", required = false) Long sloLatencyMs,
            @RequestParam(name = "slo-target", required = false) Double sloTarget,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        Map<String, Object> out = alertSink.reload(actions, routes, escalateTiers, sloLatencyMs, sloTarget);
        out.put("csrf", csrf.configSnapshot());
        return out;
    }

    /**
     * Push a synthetic alert through the pipeline and report where it lands. With {@code ?send=true} it performs
     * a live probe POST (CSRF-guarded, since it has a side effect). Admin only.
     */
    @PostMapping("/admin/alerts/selftest")
    public Map<String, Object> adminAlertsSelfTest(
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "send", defaultValue = "false") boolean send,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        if (send) csrf.require(csrfToken(csrfHeader, csrfParam)); // only the probe path has a side effect
        return alertSink.selfTest(action.isBlank() ? null : action, send);
    }

    /** Recent scheduled self-test history + flap assessment. Admin only. */
    @GetMapping("/admin/alerts/selftest")
    public Map<String, Object> adminAlertsSelfTestHistory() {
        requireAdmin();
        return alertSink.selfTestReport();
    }

    /** A short-lived per-process CSRF token for the viewer's state-changing actions. Admin only. */
    @GetMapping("/admin/alerts/csrf")
    public Map<String, Object> adminAlertsCsrf() {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("csrf", csrf.token());
        out.put("enabled", csrf.enabled());
        return out;
    }

    /** Resolve the presented CSRF token: header wins, query param is the fallback. */
    private String csrfToken(String header, String param) {
        return (header != null && !header.isBlank()) ? header : param;
    }

    /** Acknowledge a dead-letter so it no longer escalates. Admin only. */
    @PostMapping("/admin/alerts/ack")
    public Map<String, Object> adminAlertsAck(@RequestParam(name = "id") String id,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.ack(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("acked", n);
        return out;
    }

    /** Force an escalation sweep of un-acked dead-letters past the threshold. Admin only. */
    @PostMapping("/admin/alerts/escalate")
    public Map<String, Object> adminAlertsEscalate(
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.escalateStale(System.currentTimeMillis());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("escalated", n);
        out.put("stats", alertSink.stats());
        return out;
    }

    /** Bulk-acknowledge all failed dead-letters matching the filters. Admin only. */
    @PostMapping("/admin/alerts/ack-all")
    public Map<String, Object> adminAlertsAckAll(
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.ackMatching(action.isBlank() ? null : action,
                status.isBlank() ? null : status, q.isBlank() ? null : q);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("acked", n);
        return out;
    }

    /** Bulk-replay all failed dead-letters matching the filters. Admin only. */
    @PostMapping("/admin/alerts/replay-all")
    public Map<String, Object> adminAlertsReplayAll(
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "status", defaultValue = "") String status,
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.replayMatching(action.isBlank() ? null : action,
                status.isBlank() ? null : status, q.isBlank() ? null : q);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("replayed", n);
        out.put("stats", alertSink.stats());
        return out;
    }

    /**
     * Re-attempt delivery of dead-lettered alerts. With {@code ?id=...} replays a single one; otherwise all.
     * Returns the number re-enqueued. Admin only.
     */
    @PostMapping("/admin/alerts/replay")
    public Map<String, Object> adminAlertsReplay(@RequestParam(name = "id", required = false) String id,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.replay(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("replayed", n);
        out.put("stats", alertSink.stats());
        return out;
    }

    /**
     * Dry-run an alert template: render it against a sample event and return the payload + validation issues.
     * With {@code ?send=true}, also enqueues one real delivery to the default webhook. With a request body,
     * previews that template string instead of the configured one. Admin only.
     */
    @PostMapping("/admin/alerts/test")
    public Map<String, Object> adminAlertsTest(
            @RequestParam(name = "send", defaultValue = "false") boolean send,
            @RequestBody(required = false) String templateOverride,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        if (send) csrf.require(csrfToken(csrfHeader, csrfParam)); // only the side-effecting path needs the token
        String tmpl = (templateOverride != null && !templateOverride.isBlank()) ? templateOverride : null;
        return alertSink.preview(tmpl, send);
    }

    /** Purge dead-lettered alerts: all, or one with {@code ?id=...}. Admin only. */
    @DeleteMapping("/admin/alerts/failed")
    public Map<String, Object> adminAlertsPurge(@RequestParam(name = "id", required = false) String id,
            @RequestHeader(name = "X-CSRF-Token", required = false, defaultValue = "") String csrfHeader,
            @RequestParam(name = "csrf", required = false, defaultValue = "") String csrfParam) {
        requireAdmin();
        csrf.require(csrfToken(csrfHeader, csrfParam));
        int n = alertSink.purgeAll(id);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("purged", n);
        out.put("stats", alertSink.stats());
        return out;
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
        // unified observability: context-management activity + durable-memory state alongside the runs
        out.put("context", snap.getOrDefault("context", Map.of())); // folds/compactions/trims totals
        String durableNote = memory.get(MemoryStore.DEFAULT_OWNER);
        out.put("memory", Map.of(
                "workspace", MemoryStore.workspaceId(),
                "durablePresent", durableNote != null && !durableNote.isBlank(),
                "trackedFacts", memory.analytics(currentUser()).size()));
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

    /**
     * Recent runs as newline-delimited JSON (one run object per line, newest first), for piping into log
     * pipelines / external trace tooling. Each line carries the run's endpoint, session, mode, latency,
     * outcome, per-run context counts (folds/compactions/trims), and the captured event timeline. Admin only.
     */
    @GetMapping(value = "/admin/runs.ndjson", produces = "application/x-ndjson")
    public ResponseEntity<String> adminRunsNdjson(
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "endpoint", defaultValue = "") String endpoint,
            @RequestParam(name = "outcome", defaultValue = "") String outcome,
            @RequestParam(name = "session", defaultValue = "") String session) {
        requireAdmin();
        List<Map<String, Object>> runs = metrics.recentRuns(Math.max(1, Math.min(1000, limit)),
                endpoint, outcome, session);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> run : runs) {
            try {
                sb.append(mapper.writeValueAsString(run)).append('\n');
            } catch (Exception e) {
                // skip a run that can't be serialized rather than failing the whole export
            }
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/x-ndjson")
                .header("Content-Disposition", "attachment; filename=\"imini-runs.ndjson\"")
                .body(sb.toString());
    }

    /**
     * Parse a window spec into milliseconds: a number with a unit suffix s/m/h/d (e.g. "90s", "30m", "24h",
     * "7d"), a bare number of milliseconds, or "all"/"" -> -1 (meaning "since the beginning"). Unknown specs
     * fall back to 24h. Pure + static for testing.
     */
    static long parseWindowMs(String window) {
        if (window == null) return 86_400_000L;
        String w = window.trim().toLowerCase();
        if (w.isEmpty() || w.equals("all")) return -1L;
        try {
            char unit = w.charAt(w.length() - 1);
            if (Character.isDigit(unit)) return Long.parseLong(w); // bare milliseconds
            long n = Long.parseLong(w.substring(0, w.length() - 1));
            return switch (unit) {
                case 's' -> n * 1_000L;
                case 'm' -> n * 60_000L;
                case 'h' -> n * 3_600_000L;
                case 'd' -> n * 86_400_000L;
                default -> 86_400_000L;
            };
        } catch (Exception e) {
            return 86_400_000L;
        }
    }

    /**
     * Durable SLO over a time window, computed from the persisted run_history (survives restart). Unlike the
     * in-memory percentiles in /metrics (a moving window over recent runs), this covers a real time window.
     * {@code window}: e.g. "24h" (default), "7d", "30m", "90s", or "all". Admin only.
     */
    @GetMapping("/admin/slo")
    public Map<String, Object> adminSlo(@RequestParam(name = "window", defaultValue = "24h") String window) {
        requireAdmin();
        long windowMs = parseWindowMs(window);
        long since = windowMs < 0 ? 0 : Math.max(0, System.currentTimeMillis() - windowMs);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("window", window);
        out.put("windowMs", windowMs);
        out.putAll(metrics.windowStats(since));
        return out;
    }

    /**
     * Recent distributed-trace spans (OpenTelemetry data model), newest first. Each span has a W3C
     * trace_id/span_id/parent_id, name, timing, and attributes. Tracing is off unless tracing.enabled=true.
     * Admin only.
     */
    @GetMapping("/admin/traces")
    public Map<String, Object> adminTraces(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        requireAdmin();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", tracer.enabled());
        out.put("spans", tracer.recent(Math.max(1, Math.min(500, limit))));
        return out;
    }

    /** Per-tenant token usage and cost for the current month (cost_ledger). Admin only. */
    @GetMapping("/admin/cost")
    public Map<String, Object> adminCost() {
        requireAdmin();
        return cost.summary();
    }

    /** Human-readable per-tenant usage dashboard (HTML) rendered from the cost summary. Admin only. */
    @GetMapping(value = "/admin/usage", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> adminUsage() {
        requireAdmin();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(UsageDashboard.render(cost.summary()));
    }

    /** The resolved tool-capability scopes per role (capability scoping). Admin only. */
    @GetMapping("/admin/capabilities")
    public Map<String, Object> adminCapabilities() {
        requireAdmin();
        return capabilities.describe();
    }

    /** The configured per-tool rate limits. Admin only. */
    @GetMapping("/admin/tool-rate-limits")
    public Map<String, Object> adminToolRateLimits() {
        requireAdmin();
        return toolRateLimiter.describe();
    }

    /**
     * Human-readable audit-log viewer: a filterable HTML page over the audit table (same data as
     * {@code GET /audit}), surfacing capability denials, spend alerts, tool rate-limit rejections, and every
     * other audited action. Admin only.
     */
    @GetMapping(value = "/admin/audit.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> adminAuditHtml(
            @RequestParam(name = "user", defaultValue = "") String user,
            @RequestParam(name = "action", defaultValue = "") String action,
            @RequestParam(name = "target", defaultValue = "") String target,
            @RequestParam(name = "since", defaultValue = "") String since,
            @RequestParam(name = "until", defaultValue = "") String until,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        requireAdmin();
        int capped = Math.max(1, Math.min(limit, 1000));
        int off = Math.max(0, offset);
        long sinceMs = parseInstant(since);
        long untilMs = parseInstant(until);
        String u = user.isBlank() ? null : user;
        String a = action.isBlank() ? null : action;
        String t = target.isBlank() ? null : target;
        List<AuditLog.Entry> rows = audit.pageRange(u, a, t, sinceMs, untilMs, off, capped);
        int total = audit.countRange(u, a, t, sinceMs, untilMs);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(AuditDashboard.render(rows, user, action, target, since, until, off, capped, total));
    }

    /** Parse an ISO-8601 instant or epoch-millis string to epoch ms; 0 (unbounded) if blank/invalid. */
    static long parseInstant(String s) {
        if (s == null || s.isBlank()) return 0L;
        String v = s.trim();
        try {
            return Long.parseLong(v); // epoch millis
        } catch (NumberFormatException ignore) {
            // fall through to ISO parsing
        }
        try {
            return java.time.Instant.parse(v).toEpochMilli();
        } catch (Exception ignore) {
            try {
                // date-only (treat as start of that UTC day)
                return java.time.LocalDate.parse(v).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (Exception ignore2) {
                return 0L;
            }
        }
    }

    /**
     * Run the agent-evaluation suite against the live model and return the pass-rate plus per-case detail.
     * Self-skips (returns {skipped:true}) when the model is unreachable or eval is disabled. Admin only.
     */
    @PostMapping("/admin/eval")
    public Map<String, Object> adminEval() {
        requireAdmin();
        return eval.runSuite(eval.loadCases());
    }

    /**
     * The FULL persisted run history as newline-delimited JSON, paginated: up to {@code limit} runs
     * (oldest-first) with {@code ts >= since}. Page forward by passing the last line's {@code ts} as the
     * next {@code since}. Reaches the entire run_history table, not just the in-memory tail. Admin only.
     */
    @GetMapping(value = "/admin/runs/history.ndjson", produces = "application/x-ndjson")
    public ResponseEntity<String> adminRunHistoryNdjson(
            @RequestParam(name = "since", defaultValue = "0") long since,
            @RequestParam(name = "limit", defaultValue = "1000") int limit) {
        requireAdmin();
        int cap = Math.max(1, Math.min(10000, limit));
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> run : metrics.historyPage(since, cap)) {
            try {
                sb.append(mapper.writeValueAsString(run)).append('\n');
            } catch (Exception e) {
                // skip an unserializable row rather than failing the export
            }
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/x-ndjson")
                .header("Content-Disposition", "attachment; filename=\"imini-run-history.ndjson\"")
                .body(sb.toString());
    }

    /** Recent runs for one session (newest first): endpoint, mode, latency, outcome. Session read access. */
    @GetMapping("/session/runs")
    public List<Map<String, Object>> sessionRuns(
            @RequestParam(name = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(name = "limit", defaultValue = "25") int limit) {
        requireRead(sessionId);
        return metrics.recentRunsForSession(Math.max(1, Math.min(200, limit)), sessionId);
    }

    /** The grant/revoke history (newest first): the audit trail filtered to workspace-root actions. Admin only. */
    @GetMapping("/admin/roots/audit")
    public List<AuditLog.Entry> adminRootsAudit(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        requireAdmin();
        int cap = Math.max(1, Math.min(1000, limit));
        List<AuditLog.Entry> out = new java.util.ArrayList<>();
        out.addAll(audit.recent("", "grant_workspace_root", "", 0, cap));
        out.addAll(audit.recent("", "revoke_workspace_root", "", 0, cap));
        out.addAll(audit.recent("", "create_project", "", 0, cap));
        out.sort((a, b) -> Long.compare(b.ts(), a.ts())); // newest first across all actions
        return out.size() > cap ? out.subList(0, cap) : out;
    }

    /** Admin-only audit trail of privileged actions (newest first); filter by user/target. */
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
        String pinned = memory.pinned(owner);
        out.put("owner", owner);
        out.put("note", note == null ? "" : note);
        out.put("pinned", pinned == null ? "" : pinned);
        out.put("pins", memory.pinsDetailed(owner)); // with provenance: fact/source/createdAt
        out.put("effective", memory.effective(owner));
        out.put("present", (note != null && !note.isBlank()) || (pinned != null && !pinned.isBlank()));
        out.put("updatedAt", memory.updatedAt(owner));
        out.put("workspace", MemoryStore.workspaceId()); // memory is scoped per workspace + owner
        return out;
    }

    /** Hand-edit the current user's auto memory note (admin). Empty clears the auto part; pins are kept. */
    @PostMapping("/memory/durable")
    public Map<String, Object> setDurableMemory(@RequestBody Map<String, String> body) {
        requireAdmin();
        memory.setNote(currentUser(), body == null ? "" : body.getOrDefault("note", ""));
        audit.record(currentUser(), "memory", "durable", "edited");
        return durableMemory();
    }

    /** Pin a curated fact that always seeds new sessions and is never overwritten by compaction (admin). */
    @PostMapping("/memory/durable/pin")
    public Map<String, Object> pinDurableMemory(@RequestBody Map<String, String> body) {
        requireAdmin();
        memory.addPin(currentUser(), body == null ? "" : body.getOrDefault("text", ""),
                body == null ? "manual" : body.getOrDefault("source", "manual"));
        audit.record(currentUser(), "memory", "durable", "pinned");
        return durableMemory();
    }

    /** Remove a pinned fact (admin). */
    @PostMapping("/memory/durable/unpin")
    public Map<String, Object> unpinDurableMemory(@RequestBody Map<String, String> body) {
        requireAdmin();
        memory.removePin(currentUser(), body == null ? "" : body.getOrDefault("text", ""));
        audit.record(currentUser(), "memory", "durable", "unpinned");
        return durableMemory();
    }

    /** Per-fact durable-memory usage analytics: how often each fact is injected into a session / recalled. */
    @GetMapping("/memory/analytics")
    public Map<String, Object> memoryAnalytics() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("facts", memory.analytics(currentUser()));
        out.put("workspace", MemoryStore.workspaceId());
        return out;
    }

    /** Run a durable-memory hygiene pass: prune long-unused auto facts (admin). Returns what was pruned. */
    @PostMapping("/memory/hygiene")
    public Map<String, Object> memoryHygiene() {
        requireAdmin();
        Map<String, Object> report = memory.hygiene(currentUser());
        audit.record(currentUser(), "memory", "hygiene", "pruned=" + ((List<?>) report.getOrDefault("pruned", List.of())).size());
        return report;
    }

    /** Clear the current user's durable memory note (admin). Pinned facts are preserved. */
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
        // a too-large single request is best split: plan mode breaks it into steps that each fit the window
        out.put("recommendPlanMode", estimated > cap);
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
