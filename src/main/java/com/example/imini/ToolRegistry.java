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

    private final SubAgent subAgent;
    private final AgentRegistry agents;

    public ToolRegistry(BuiltinTools builtins, CodebaseTools codebase, SubAgent subAgent,
                        McpManager mcp, RetrievalService retrieval, SkillService skills,
                        AgentRegistry agents) {
        this.subAgent = subAgent;
        this.agents = agents;
        for (Tool t : builtins.all()) register(t);
        for (Tool t : codebase.all()) register(t);   // glob, grep, repo_tree, read_many, git_status, git_diff
        register(delegateTool(subAgent));
        register(delegateAgentTool());
        register(retrieval.searchTool());          // search_memory (RAG over the workspace)
        register(retrieval.indexTool());            // index_workspace
        register(skills.loadSkillTool());          // load_skill (progressive disclosure)
        register(skills.saveSkillTool());          // save_skill (capture knowledge)
        register(skills.refreshSkillsTool());      // refresh_skills (pull remote repos)
        register(skills.searchSkillsTool());       // search_skills (registry, with provenance)
        register(skills.installSkillTool());       // install_skill (hash-verified)
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

    /** Run a named subagent (from AgentRegistry) on a task, scoping it to that agent's tools. */
    /** Read-only default tool set for a forked skill that declares no allowed_tools. */
    private static final java.util.List<String> DEFAULT_FORK_TOOLS =
            java.util.List.of("read_file", "view", "grep", "repo_tree", "read_many");

    public String delegateToAgent(String name, String task, RunSink sink) throws Exception {
        AgentLibrary.AgentDef def = agents.byName(name);
        if (def == null) {
            return "No subagent named \"" + name + "\". Use /agents to list them.";
        }
        return delegate(SessionContext.sessionId(), def.body(), task, def.tools(), sink);
    }

    /** Run a skill with {@code context: fork} in an isolated sub-agent; only its summary returns. */
    public String delegateSkillFork(String sessionId, SkillLibrary.Skill skill, String args, RunSink sink)
            throws Exception {
        String body = SkillInvocation.substitute(skill.body(), args);
        java.util.List<String> toolNames =
                (skill.allowedTools() == null || skill.allowedTools().isEmpty())
                        ? DEFAULT_FORK_TOOLS : skill.allowedTools();
        String task = (args == null || args.isBlank()) ? "Follow the instructions above." : args;
        return delegate(sessionId, body, task, toolNames, sink);
    }

    /** General delegation: run a sub-agent with a system prompt, task, and an allow-list of tool names. */
    public String delegate(String sessionId, String systemPrompt, String task,
                           java.util.List<String> toolNames, RunSink sink) throws Exception {
        Map<String, Tool> scoped = new LinkedHashMap<>();
        if (toolNames != null) {
            for (String tn : toolNames) {
                Tool t = tools.get(tn);
                if (t != null) scoped.put(tn, t);
            }
        }
        String sys = systemPrompt
                + (scoped.isEmpty() ? "" : "\n\nYou may use only these tools: " + String.join(", ", scoped.keySet()) + ".")
                + "\nReturn only your final answer in plain text.";
        return subAgent.run(sessionId, sys, task, scoped, sink);
    }

    private Tool delegateAgentTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> nameP = new LinkedHashMap<>();
        nameP.put("type", "string");
        nameP.put("description", "The subagent name (see /agents): e.g. explore, review, debug, research.");
        Map<String, Object> taskP = new LinkedHashMap<>();
        taskP.put("type", "string");
        taskP.put("description", "The task for the subagent, as a clear self-contained instruction.");
        props.put("name", nameP);
        props.put("task", taskP);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", java.util.List.of("name", "task"));
        return new Tool("delegate_agent",
                "Delegate a task to a named, tool-scoped subagent that runs in its own isolated loop and "
                + "returns only its final answer. Use for focused subtasks (explore the codebase, review a "
                + "diff, debug, research). Names come from /agents.",
                schema, false, args -> {
                    try {
                        String name = String.valueOf(args.getOrDefault("name", "")).trim();
                        String task = String.valueOf(args.getOrDefault("task", "")).trim();
                        return delegateToAgent(name, task, SessionContext.sink());
                    } catch (Exception e) {
                        return "ERROR: " + e.getMessage();
                    }
                });
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
