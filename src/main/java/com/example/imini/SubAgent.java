package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A second agent loop. The main agent calls it via the delegate_research tool. It runs its own
 * conversation with only web_search + web_fetch, does the multi-step searching and reading, and
 * returns ONLY its final summary. All of its noisy intermediate context (search result lists, full
 * page dumps) lives in this sub-conversation and is discarded -- the main agent's window only ever
 * sees the clean result. That isolation is the whole point of sub-agents.
 */
@Component
public class SubAgent {

    private static final String SYSTEM_PROMPT = """
            You are a research sub-agent. You have two tools: web_search and web_fetch.
            Given a research task, search for relevant sources, open the most promising one or two
            with web_fetch, and then write a concise answer (a few sentences) that directly addresses
            the task, mentioning the sources you used. Do not ask the user questions. When you are
            done, reply in plain text with NO tool calls.
            """;

    private final AgentEngine engine;
    private final BuiltinTools builtins;

    public SubAgent(AgentEngine engine, BuiltinTools builtins) {
        this.engine = engine;
        this.builtins = builtins;
    }

    public String run(String task) throws Exception {
        Map<String, Tool> webTools = new LinkedHashMap<>();
        Tool search = builtins.webSearch();
        Tool fetch = builtins.webFetch();
        webTools.put(search.name, search);
        webTools.put(fetch.name, fetch);
        // gate == null: the sub-agent has only read-only tools, so no permission gate is needed.
        return engine.run(SYSTEM_PROMPT, task, webTools, null, "sub");
    }
}
