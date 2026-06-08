package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE HARNESS, shared by the main agent and the sub-agent. think -> act -> observe loop.
 *
 * Three stop conditions protect against runaway behavior:
 *   - MAX_ITERATIONS  : the model only gets so many turns;
 *   - a wall-clock deadline (agent.deadline-seconds): the whole run is time-budgeted;
 *   - duplicate-call detection: if the model asks for the exact same tool+args repeatedly, the
 *     harness stops re-running it and nudges (then bails) instead of looping forever.
 *
 * (Per-generation runaway -- the "Live Updates" repetition -- is bounded inside LlamaClient.)
 */
@Component
public class AgentEngine {

    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_DUP_STRIKES = 3;   // repeated identical calls before we give up

    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", Pattern.DOTALL);

    private final LlamaClient llama;
    private final ContextManager context;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.stream:true}")
    private boolean stream;
    @Value("${agent.deadline-seconds:120}")
    private int deadlineSeconds;

    public AgentEngine(LlamaClient llama, ContextManager context) {
        this.llama = llama;
        this.context = context;
    }

    /** One-shot: builds [system, user] and returns just the answer. */
    public String run(String systemPrompt,
                      String userMessage,
                      Map<String, Tool> tools,
                      PermissionGate gate,
                      String label) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        messages.add(msg("user", userMessage));
        return converse(messages, tools, gate, label).answer();
    }

    /**
     * Multi-turn: runs the loop over an existing history (which must already include the system
     * message and the latest user message) and returns both the answer and the final history so a
     * session can be persisted.
     */
    public AgentResult converse(List<Map<String, Object>> startingMessages,
                                Map<String, Tool> tools,
                                PermissionGate gate,
                                String label) throws Exception {

        List<Map<String, Object>> messages = new ArrayList<>(startingMessages);
        List<Map<String, Object>> specs = specsFor(tools);

        long deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L;
        Map<String, Integer> callCounts = new HashMap<>();
        int dupStrikes = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (System.nanoTime() > deadline) {
                System.out.println("\n[guard:" + label + "] time budget of " + deadlineSeconds + "s exceeded.");
                return new AgentResult("[stopped: exceeded the " + deadlineSeconds + "s time budget without "
                        + "reaching a final answer. The task may be too large, or the model may be stuck.]", messages);
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
                return new AgentResult(
                        (answer == null || answer.isBlank()) ? "[model returned no text]" : answer, messages);
            }

            for (Map<String, Object> call : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String name = String.valueOf(fn.get("name"));
                String id = call.get("id") == null ? null : String.valueOf(call.get("id"));
                Map<String, Object> args = parseArgs(fn.get("arguments"));

                String signature = name + "|" + args;
                int count = callCounts.merge(signature, 1, Integer::sum);

                String result;
                Tool tool = tools.get(name);
                if (tool == null) {
                    result = "ERROR: unknown tool '" + name + "'";
                } else if (count > 2) {
                    // already executed this exact call twice; don't run it again
                    dupStrikes++;
                    result = "NOTE: you already called '" + name + "' with these exact arguments "
                            + (count - 1) + " time(s) and got the same result. Do NOT call it again. "
                            + "Answer the user with the information you already have.";
                    System.out.println("[guard:" + label + "] suppressed duplicate call to " + name);
                } else if (tool.mutating && (gate == null || !gate.confirm(name, args))) {
                    result = gate == null
                            ? "DENIED: mutating tools are not allowed in this context."
                            : "DENIED: the user did not approve this action.";
                } else {
                    System.out.println("[" + label + ":tool] " + name + " " + args);
                    result = tool.executor.apply(args);
                }
                messages.add(toolResult(id, name, result));
            }

            if (dupStrikes >= MAX_DUP_STRIKES) {
                System.out.println("\n[guard:" + label + "] too many repeated calls; stopping.");
                return new AgentResult("[stopped: the model kept repeating the same tool call without making "
                        + "progress, so the harness ended the run to avoid an endless loop.]", messages);
            }
        }
        return new AgentResult("[stopped: reached " + MAX_ITERATIONS + " iterations without a final answer]", messages);
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
