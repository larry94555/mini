package com.example.imini;

import org.springframework.stereotype.Component;

/**
 * The MAIN agent. Now a thin wrapper: it owns the system prompt and the full toolset (including the
 * permission gate and the delegate_research tool), and hands the actual loop to AgentEngine -- the
 * same engine the SubAgent uses. Streaming and context compaction happen inside the engine.
 */
@Component
public class AgentLoop {

    private static final String SYSTEM_PROMPT = """
            You are a small autonomous agent running inside a tool-using harness.
            Think step by step. When you need information from the file system, the shell, or a known
            web page, call the appropriate tool instead of guessing.
            For open-ended questions that require searching the web and reading several sources, call
            delegate_research with a clear task and use the summary it returns.
            Call only the tools you need, then wait for their results before continuing.
            When you have enough information, reply to the user in plain text and do NOT call any more tools.
            """;

    private final AgentEngine engine;
    private final ToolRegistry registry;
    private final PermissionGate gate;

    public AgentLoop(AgentEngine engine, ToolRegistry registry, PermissionGate gate) {
        this.engine = engine;
        this.registry = registry;
        this.gate = gate;
    }

    public String run(String userQuestion) throws Exception {
        return engine.run(SYSTEM_PROMPT, userQuestion, registry.tools(), gate, "main");
    }
}
