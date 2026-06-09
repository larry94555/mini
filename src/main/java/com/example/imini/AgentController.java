package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP surface.
 *   POST /ask         {"question":"...","mode":?}                    one-shot, no memory
 *   POST /chat        {"sessionId":"...?","message":"...","mode":?}  multi-turn; returns the sessionId
 *   GET  /sessions                                                   list known session ids
 *   GET  /todos                                                      current task checklist
 *   POST /rewind                                                     undo the most recent file edit
 *   GET  /checkpoints                                                list rewind points
 *   POST /interrupt                                                  stop the run in progress
 *   POST /steer       {"message":"..."}                              inject guidance into the run
 *
 * mode = ask (default) | auto | plan. Call /interrupt or /steer from a SECOND terminal while a run
 * is going.
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final SessionStore sessions;
    private final CheckpointStore checkpoints;
    private final TodoStore todos;
    private final InterruptService interrupt;

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints,
                           TodoStore todos, InterruptService interrupt) {
        this.loop = loop;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
        this.todos = todos;
        this.interrupt = interrupt;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        return Map.of("answer", loop.run(body.getOrDefault("question", ""), parseMode(body.get("mode"))));
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString().substring(0, 8);
        }
        String answer = loop.chat(sessionId, body.getOrDefault("message", ""), parseMode(body.get("mode")));
        return Map.of("sessionId", sessionId, "answer", answer);
    }

    @GetMapping("/sessions")
    public List<String> sessions() {
        return sessions.list();
    }

    @GetMapping("/todos")
    public Map<String, Object> todos() {
        return Map.of("todos", todos.get(), "rendered", todos.render());
    }

    @PostMapping("/rewind")
    public Map<String, String> rewind() {
        return Map.of("result", checkpoints.rewindLast());
    }

    @GetMapping("/checkpoints")
    public List<String> checkpoints() {
        return checkpoints.list();
    }

    @PostMapping("/interrupt")
    public Map<String, String> interrupt() {
        interrupt.interrupt();
        return Map.of("result", "interrupt requested; the run will stop at the next checkpoint.");
    }

    @PostMapping("/steer")
    public Map<String, String> steer(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        interrupt.steer(message);
        return Map.of("result", "steering queued: " + message);
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
