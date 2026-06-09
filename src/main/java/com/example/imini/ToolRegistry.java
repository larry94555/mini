package com.example.imini;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the MAIN agent's toolset: every builtin tool plus a delegate_research tool that hands a
 * research task to the SubAgent. Add a Tool here and the model can use it immediately -- no model
 * change, just a new function + description.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(BuiltinTools builtins, SubAgent subAgent, McpManager mcp, RetrievalService retrieval) {
        for (Tool t : builtins.all()) register(t);
        register(delegateTool(subAgent));
        register(retrieval.searchTool());          // search_memory (RAG over the workspace)
        register(retrieval.indexTool());            // index_workspace
        for (Tool t : mcp.tools()) register(t);   // external MCP-server tools (off unless mcp.json exists)
    }

    private void register(Tool t) {
        tools.put(t.name, t);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    /** name -> Tool, handed to the engine. */
    public Map<String, Tool> tools() {
        return tools;
    }

    private Tool delegateTool(SubAgent subAgent) {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("type", "string");
        task.put("description", "A self-contained research question for the sub-agent to investigate.");
        props.put("task", task);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("task"));

        return new Tool("delegate_research",
                "Delegate an open-ended web research task to a sub-agent. It searches and reads "
                        + "pages on its own and returns a concise summary. Use this when answering "
                        + "needs searching plus reading several sources.",
                schema, false, args -> {
            try {
                Object t = args.get("task");
                return subAgent.run(SessionContext.sessionId(),
                        t == null ? "" : String.valueOf(t), SessionContext.sink());
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }
}
