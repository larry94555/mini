package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.imini.PermissionService.Decision;
import com.example.imini.PermissionService.Mode;

/**
 * THE HARNESS, shared by the main agent and the sub-agent. think -> act -> observe loop.
 *
 * Tier 2 additions:
 *   - PERMISSION MODES via PermissionService (ASK / AUTO / PLAN). In PLAN mode mutating tools are
 *     recorded but never executed, and the proposed plan is appended to the answer.
 *   - PARALLEL TOOLS: within a single model turn, read-only tool calls run concurrently; mutating
 *     calls run inline (so permission prompts stay sequential).
 *   - TOOL-OUTPUT TRIMMING: each result is condensed by ContextManager before entering history.
 *
 * Stop conditions (unchanged): MAX_ITERATIONS, a wall-clock deadline, and duplicate-call detection.
 */
@Component
public class AgentEngine {

    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_DUP_STRIKES = 3;

    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", Pattern.DOTALL);

    private final LlamaClient llama;
    private final ContextManager context;
    private final PermissionService permissions;
    private final ObjectMapper mapper = new ObjectMapper();

    // unbounded so nested sub-agents (which also submit here) can't deadlock a fixed pool
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "imini-tool");
        t.setDaemon(true);
        return t;
    });

    @Value("${agent.stream:true}")
    private boolean stream;
    @Value("${agent.deadline-seconds:120}")
    private int deadlineSeconds;
    @Value("${agent.parallel-tools:true}")
    private boolean parallelTools;

    public AgentEngine(LlamaClient llama, ContextManager context, PermissionService permissions) {
        this.llama = llama;
        this.context = context;
        this.permissions = permissions;
    }

    /** One-shot: builds [system, user] and returns just the answer. */
    public String run(String systemPrompt, String userMessage, Map<String, Tool> tools,
                      Mode mode, String label) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        messages.add(msg("user", userMessage));
        return converse(messages, tools, mode, label).answer();
    }

    /** Multi-turn: runs over an existing history; returns answer + final history for persistence. */
    public AgentResult converse(List<Map<String, Object>> startingMessages, Map<String, Tool> tools,
                                Mode mode, String label) throws Exception {

        List<Map<String, Object>> messages = new ArrayList<>(startingMessages);
        List<Map<String, Object>> specs = specsFor(tools);

        long deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L;
        Map<String, Integer> callCounts = new HashMap<>();
        List<String> plan = new ArrayList<>();
        int dupStrikes = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (System.nanoTime() > deadline) {
                System.out.println("\n[guard:" + label + "] time budget of " + deadlineSeconds + "s exceeded.");
                return new AgentResult("[stopped: exceeded the " + deadlineSeconds + "s time budget without "
                        + "reaching a final answer.]" + planSuffix(plan), messages);
            }

            messages = context.compactIfNeeded(messages, label);

            Map<String, Object> assistant;
            if (stream) {
                System.out.print("\n[" + label + " thinking] ");
                assistant = llama.chatStream(messages, specs, token -> System.out.print(token));
                System.out.println();
            } else {
                assistant = llama.chat(messages, specs);
            }
            messages.add(assistant);

            List<Map<String, Object>> toolCalls = extractToolCalls(assistant);
            if (toolCalls.isEmpty()) {
                String answer = contentOf(assistant);
                if (answer == null || answer.isBlank()) answer = "[model returned no text]";
                return new AgentResult(answer + planSuffix(plan), messages);
            }

            // Resolve every call up front.
            List<CallInfo> infos = new ArrayList<>();
            for (Map<String, Object> call : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String name = String.valueOf(fn.get("name"));
                String id = call.get("id") == null ? null : String.valueOf(call.get("id"));
                Map<String, Object> args = parseArgs(fn.get("arguments"));
                infos.add(new CallInfo(name, id, args, tools.get(name)));
            }

            // Start read-only calls in parallel.
            Map<CallInfo, Future<String>> futures = new IdentityHashMap<>();
            if (parallelTools) {
                for (CallInfo ci : infos) {
                    if (ci.tool != null && !ci.tool.mutating) {
                        futures.put(ci, pool.submit(() -> safeExec(ci.tool, ci.args)));
                    }
                }
            }

            boolean parallelNote = parallelTools && futures.size() > 1;

            // Process in original order so each tool_result lines up with its call.
            for (CallInfo ci : infos) {
                String result;
                if (ci.tool == null) {
                    result = "ERROR: unknown tool '" + ci.name + "'";
                } else if (!ci.tool.mutating) {
                    System.out.println("[" + label + ":tool] " + ci.name + " " + ci.args
                            + (parallelNote ? " (parallel)" : ""));
                    Future<String> f = futures.get(ci);
                    result = (f != null) ? join(f) : safeExec(ci.tool, ci.args);
                } else {
                    String signature = ci.name + "|" + ci.args;
                    int count = callCounts.merge(signature, 1, Integer::sum);
                    if (count > 2) {
                        dupStrikes++;
                        result = "NOTE: you already called '" + ci.name + "' with these exact arguments "
                                + (count - 1) + " time(s). Do NOT call it again; answer with what you have.";
                        System.out.println("[guard:" + label + "] suppressed duplicate call to " + ci.name);
                    } else {
                        Decision d = permissions.decide(ci.name, true, ci.args, mode);
                        switch (d.kind()) {
                            case ALLOW -> {
                                System.out.println("[" + label + ":tool] " + ci.name + " " + ci.args);
                                result = safeExec(ci.tool, ci.args);
                            }
                            case DENY -> result = "DENIED: " + d.note() + ".";
                            case RECORD_PLAN -> {
                                String desc = ci.name + " " + ci.args;
                                plan.add(desc);
                                System.out.println("[" + label + ":plan] would run " + desc);
                                result = "[plan mode] Recorded (not executed): " + desc
                                        + ". Continue planning; do not assume it ran.";
                            }
                            default -> result = "DENIED.";
                        }
                    }
                }
                messages.add(toolResult(ci.id, ci.name, context.condenseToolResult(result)));
            }

            if (dupStrikes >= MAX_DUP_STRIKES) {
                System.out.println("\n[guard:" + label + "] too many repeated calls; stopping.");
                return new AgentResult("[stopped: the model kept repeating the same tool call.]"
                        + planSuffix(plan), messages);
            }
        }
        return new AgentResult("[stopped: reached " + MAX_ITERATIONS + " iterations without a final answer]"
                + planSuffix(plan), messages);
    }

    private String planSuffix(List<String> plan) {
        if (plan.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nProposed plan (PLAN MODE - nothing was executed):");
        for (String p : plan) sb.append("\n  - ").append(p);
        sb.append("\n\nRe-send this request with mode \"ask\" or \"auto\" to carry it out.");
        return sb.toString();
    }

    private String safeExec(Tool tool, Map<String, Object> args) {
        try {
            return tool.executor.apply(args);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private String join(Future<String> f) {
        try {
            return f.get();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** A resolved tool call. */
    private static final class CallInfo {
        final String name;
        final String id;
        final Map<String, Object> args;
        final Tool tool;

        CallInfo(String name, String id, Map<String, Object> args, Tool tool) {
            this.name = name;
            this.id = id;
            this.args = args;
            this.tool = tool;
        }
    }

    // ---------------------------------------------------------------------
    // Message + tool-call helpers
    // ---------------------------------------------------------------------

    public List<Map<String, Object>> specsFor(Map<String, Tool> tools) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name);
            fn.put("description", t.description);
            fn.put("parameters", t.parameters);
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", fn);
            list.add(spec);
        }
        return list;
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private Map<String, Object> toolResult(String toolCallId, String name, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "tool");
        if (toolCallId != null) m.put("tool_call_id", toolCallId);
        m.put("name", name);
        m.put("content", content);
        return m;
    }

    private String contentOf(Map<String, Object> assistant) {
        Object c = assistant.get("content");
        return c == null ? null : String.valueOf(c);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> assistant) {
        Object tc = assistant.get("tool_calls");
        if (tc instanceof List<?> list && !list.isEmpty()) {
            return (List<Map<String, Object>>) tc;
        }
        String content = contentOf(assistant);
        if (content == null) return List.of();

        List<Map<String, Object>> calls = new ArrayList<>();
        Matcher m = TOOL_CALL_TAG.matcher(content);
        int idx = 0;
        while (m.find()) {
            try {
                Map<String, Object> obj = mapper.readValue(m.group(1), Map.class);
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", String.valueOf(obj.get("name")));
                Object a = obj.get("arguments");
                fn.put("arguments", a instanceof String ? a
                        : mapper.writeValueAsString(a == null ? Map.of() : a));
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", "call_" + (idx++));
                call.put("type", "function");
                call.put("function", fn);
                calls.add(call);
            } catch (Exception ignore) {
                // not a valid tool call block; skip
            }
        }
        return calls;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(Object arguments) {
        try {
            if (arguments instanceof Map<?, ?> mp) return (Map<String, Object>) mp;
            if (arguments instanceof String s && !s.isBlank()) return mapper.readValue(s, Map.class);
        } catch (Exception ignore) {
            // fall through to empty args
        }
        return new LinkedHashMap<>();
    }
}
