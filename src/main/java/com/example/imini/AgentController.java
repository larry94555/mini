package com.example.imini;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP surface.
 *   POST /ask         {"question":"..."}                 one-shot, no memory
 *   POST /chat        {"sessionId":"...?","message":"..."} multi-turn; returns the sessionId to reuse
 *   GET  /sessions                                        list known session ids
 *   POST /rewind                                          undo the most recent file edit
 *   GET  /checkpoints                                     list available rewind points
 *
 * Each call blocks while the agent loops (and while any permission prompt waits in the server
 * console). Fine for a single-user learning setup.
 */
@RestController
public class AgentController {

    private final AgentLoop loop;
    private final SessionStore sessions;
    private final CheckpointStore checkpoints;

    public AgentController(AgentLoop loop, SessionStore sessions, CheckpointStore checkpoints) {
        this.loop = loop;
        this.sessions = sessions;
        this.checkpoints = checkpoints;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        return Map.of("answer", loop.run(body.getOrDefault("question", "")));
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString().substring(0, 8);
        }
        String answer = loop.chat(sessionId, body.getOrDefault("message", ""));
        return Map.of("sessionId", sessionId, "answer", answer);
    }

    @GetMapping("/sessions")
    public List<String> sessions() {
        return sessions.list();
    }

    @PostMapping("/rewind")
    public Map<String, String> rewind() {
        return Map.of("result", checkpoints.rewindLast());
    }

    @GetMapping("/checkpoints")
    public List<String> checkpoints() {
        return checkpoints.list();
    }
}
