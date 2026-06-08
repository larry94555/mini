package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE HARNESS. Everything that makes this an "agent" rather than a single chat call lives here,
 * and it is small on purpose. The flow is:
 *
 *   1. send (system + user + history) and the tool specs to the model
 *   2. model replies with text and/or tool-call requests
 *   3. if no tool calls -> that text is the final answer, return it
 *   4. otherwise the harness EXECUTES each requested tool (gating mutating ones),
 *      appends the results, and loops back to step 1
 *
 * The model decides WHAT to do; this loop carries it out and feeds reality back. No reasoning about
 * file contents or HTML happens in this class -- only plumbing.
 */
@Component
public class AgentLoop {

    private static final int MAX_ITERATIONS = 10;

    private static final String SYSTEM_PROMPT = """
            You are a small autonomous agent running inside a tool-using harness.
            Think step by step. When you need information from the file system, the shell, or the web,
            call the appropriate tool instead of guessing or inventing an answer.
            Call only the tools you need, then wait for their results before continuing.
            When you have gathered enough information, reply to the user directly in plain text and
            do NOT call any more tools.
            """;

    private final LlamaClient llama;
    private final ToolRegistry registry;
    private final PermissionGate gate;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>\\s*(\\{.*?})\\s*</tool_call>", Pattern.DOTALL);

    public AgentLoop(LlamaClient llama, ToolRegistry registry, PermissionGate gate) {
        this.llama = llama;
        this.registry = registry;
        this.gate = gate;
    }

    public String run(String userQuestion) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", SYSTEM_PROMPT));
        messages.add(msg("user", userQuestion));

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            Map<String, Object> assistant = llama.chat(messages, registry.specs());
            messages.add(assistant); // re-append exactly what the model returned

            List<Map<String, Object>> toolCalls = extractToolCalls(assistant);
            if (toolCalls.isEmpty()) {
                String answer = contentOf(assistant);
                return (answer == null || answer.isBlank()) ? "[model returned no text]" : answer;
            }

            for (Map<String, Object> call : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String name = String.valueOf(fn.get("name"));
                String id = call.get("id") == null ? null : String.valueOf(call.get("id"));
                Map<String, Object> args = parseArgs(fn.get("arguments"));

                String result;
                Tool tool = registry.get(name);
                if (tool == null) {
                    result = "ERROR: unknown tool '" + name + "'";
                } else if (tool.mutating && !gate.confirm(name, args)) {
                    result = "DENIED: the user did not approve this action.";
                } else {
                    System.out.println("[tool] " + name + " " + args);
                    result = tool.executor.apply(args);
                }
                messages.add(toolResult(id, name, result));
            }
        }
        return "[stopped: reached " + MAX_ITERATIONS + " iterations without a final answer]";
    }

    // ---------------------------------------------------------------------
    // Message helpers
    // ---------------------------------------------------------------------

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

    /**
     * Prefer the native OpenAI tool_calls field. If the (small) model instead emitted tool calls as
     * &lt;tool_call&gt;{...}&lt;/tool_call&gt; text -- a common Qwen failure mode -- parse those too.
     */
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
