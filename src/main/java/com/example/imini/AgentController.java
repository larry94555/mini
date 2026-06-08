package com.example.imini;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /ask  {"question": "..."}  ->  {"answer": "..."}
 *
 * The request thread blocks while the agent loops (and while any permission prompt waits for you
 * to answer in the server console). Fine for a single-user learning setup.
 */
@RestController
public class AgentController {

    private final AgentLoop loop;

    public AgentController(AgentLoop loop) {
        this.loop = loop;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> body) throws Exception {
        String question = body.getOrDefault("question", "");
        return Map.of("answer", loop.run(question));
    }
}
