package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints,
                           TodoStore todos, InterruptService interrupt, RunService runService,
                           RetrievalService retrieval, Metrics metrics) {
        this.loop = loop;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.todos = todos;
        this.interrupt = interrupt;
        this.runService = runService;
        this.retrieval = retrieval;
        this.metrics = metrics;
    }

    // ---- blocking ----------------------------------------------------------

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        final String sessionId = "oneshot-" + UUID.randomUUID().toString().substring(0, 8);
        final Mode mode = parseMode(body.get("mode"));
        final String q = body.getOrDefault("question", "");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        try {
            String answer = runService.runBounded(() -> loop.run(sessionId, q, mode, new ConsoleSink()));
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
        final Mode mode = parseMode(body.get("mode"));
        final String message = body.getOrDefault("message", "");
        metrics.inc("runs_started");
        long t0 = System.nanoTime();
        try {
            String answer = runService.runBounded(() -> loop.chat(sessionId, message, mode, new ConsoleSink()));
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
        final Mode mode = parseMode(body.get("mode"));
        final String message = body.getOrDefault("message", "");
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            try {
                send(emitter, "session", sessionId);
                String answer = runService.runBounded(() -> loop.chat(sessionId, message, mode, sink));
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
        SseEmitter emitter = new SseEmitter(0L);
        metrics.inc("runs_started");
        runService.submitAsync(() -> {
            RunSink sink = sseSink(emitter);
            long t0 = System.nanoTime();
            try {
                send(emitter, "session", sessionId);
                String answer = runService.runBounded(() -> loop.run(sessionId, q, mode, sink));
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
        interrupt.interrupt(sessionId);
        return Map.of("result", "interrupt requested for session " + sessionId
                + "; it will stop at the next checkpoint.");
    }

    @PostMapping("/steer")
    public Map<String, String> steer(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        String message = body.getOrDefault("message", "");
        if (sessionId.isBlank()) return Map.of("result", "provide a sessionId to steer.");
        interrupt.steer(sessionId, message);
        return Map.of("result", "steering queued for session " + sessionId + ": " + message);
    }

    @GetMapping("/todos")
    public Map<String, Object> todos(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        return Map.of("sessionId", sessionId, "todos", todos.get(sessionId), "rendered", todos.render(sessionId));
    }

    // ---- status / misc -----------------------------------------------------

    @GetMapping("/sessions")
    public List<String> sessions() {
        return sessions.list();
    }

    /** A single session's stored messages (for the UI to render prior history on switch). */
    @GetMapping("/session")
    public List<Map<String, Object>> session(@RequestParam(name = "id") String id) {
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

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @PostMapping("/rewind")
    public Map<String, String> rewind(@RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", "");
        if (sessionId.isBlank()) return Map.of("result", "provide a sessionId to rewind.");
        return Map.of("result", checkpoints.rewindLast(sessionId));
    }

    @GetMapping("/checkpoints")
    public List<String> checkpoints(@RequestParam(name = "sessionId", defaultValue = "default") String sessionId) {
        return checkpoints.list(sessionId);
    }

    // ---- retrieval / memory ------------------------------------------------

    @PostMapping("/index")
    public Map<String, String> index() {
        return Map.of("result", retrieval.index());
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

    private static RunSink sseSink(SseEmitter emitter) {
        return new RunSink() {
            @Override public void token(String text) { send(emitter, "token", text); }
            @Override public void log(String line) { send(emitter, "log", line); }
        };
    }

    private static void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data == null ? "" : data));
        } catch (Exception e) {
            // client disconnected or emitter completed; nothing to do
        }
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
