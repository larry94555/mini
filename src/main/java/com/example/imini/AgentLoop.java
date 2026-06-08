package com.example.imini;

import com.example.imini.PermissionService.Mode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The MAIN agent. Owns the system prompt and the full toolset, and hands the loop to AgentEngine.
 * Permission decisions (including plan mode) are made inside the engine via PermissionService, so
 * each call just passes the requested Mode through.
 *
 *   run(question, mode)            -> one-shot, no memory (used by /ask)
 *   chat(sessionId, message, mode) -> multi-turn; loads, extends, and persists history (used by /chat)
 */
@Component
public class AgentLoop {

    private static final String SYSTEM_PROMPT = """
            You are a small autonomous agent running inside a tool-using harness.
            Think step by step. When you need information from the file system, the shell, or a known
            web page, call the appropriate tool instead of guessing.
            For a task with several steps, call todo_write first to lay out the steps, then mark each
            in_progress and completed as you go.
            To change part of an existing file, prefer edit_file (a targeted, exact replacement) over
            write_file. Use view to read a file with line numbers before editing it.
            For open-ended questions that require searching the web and reading several sources, call
            delegate_research with a clear task and use the summary it returns.
            Call only the tools you need, then wait for their results before continuing.
            When you have enough information, reply to the user in plain text and do NOT call any more tools.
            """;

    private final AgentEngine engine;
    private final ToolRegistry registry;
    private final SessionStore sessions;

    public AgentLoop(AgentEngine engine, ToolRegistry registry, SessionStore sessions) {
        this.engine = engine;
        this.registry = registry;
        this.sessions = sessions;
    }

    /** One-shot, ephemeral. */
    public String run(String userQuestion, Mode mode) throws Exception {
        return engine.run(SYSTEM_PROMPT, userQuestion, registry.tools(), mode, "main");
    }

    /** Multi-turn: continues (or starts) the conversation stored under sessionId. */
    public String chat(String sessionId, String userMessage, Mode mode) throws Exception {
        List<Map<String, Object>> history = sessions.get(sessionId);
        if (history == null) {
            history = new ArrayList<>();
            history.add(message("system", SYSTEM_PROMPT));
        }
        history.add(message("user", userMessage));

        AgentResult result = engine.converse(history, registry.tools(), mode, "main");
        sessions.save(sessionId, result.messages());
        return result.answer();
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
