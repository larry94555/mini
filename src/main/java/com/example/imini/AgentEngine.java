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
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.imini.PermissionService.Decision;
import com.example.imini.PermissionService.Mode;

/**
 * THE HARNESS, shared by the main agent and the sub-agent. think -> act -> observe loop.
 *
 * Tier 2: permission modes (ASK/AUTO/PLAN), parallel read-only tools, tool-output trimming.
 * Tier 3: interruptibility + steering (main loop only), prompt-injection fencing, hooks.
 * Step 3 (concurrency): every run carries a sessionId (per-session interrupt/steer/todos/permissions)
 *         and a RunSink (tokens + logs go to the caller's stream, not a shared System.out), so many
 *         runs can stream to different clients at once. The sessionId + sink are also published to
 *         tool executors via SessionContext for the duration of each tool call.
 *
 * Stop conditions: MAX_ITERATIONS, wall-clock deadline, duplicate-call detection, user interrupt.
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
    private final InterruptService interrupt;
    private final HookService hooks;
    private final Metrics metrics;
    private final RunRecorder recorder;
    private final CapabilityService capabilities;
    private final ToolRateLimiter toolRateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();

    // Optional: in-process user extensions observe loop lifecycle events. Field-injected so the
    // constructor (and every test that builds the engine directly) is unchanged; null when absent.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExtensionRegistry extensions;

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "imini-tool");
        t.setDaemon(true);
        return t;
    });

    @Value("${agent.stream:true}")
    private boolean stream;
    @Value("${agent.deadline-seconds:120}")
    private int deadlineSeconds;
    @Value("${agent.deadline-action:ask}")
    private String deadlineAction;
    @Value("${agent.parallel-tools:true}")
    private boolean parallelTools;

    public AgentEngine(LlamaClient llama, ContextManager context,
                       PermissionService permissions, InterruptService interrupt, HookService hooks,
                       Metrics metrics, RunRecorder recorder, CapabilityService capabilities,
                       ToolRateLimiter toolRateLimiter) {
        this.llama = llama;
        this.context = context;
        this.permissions = permissions;
        this.interrupt = interrupt;
        this.hooks = hooks;
        this.metrics = metrics;
        this.recorder = recorder;
        this.capabilities = capabilities;
        this.toolRateLimiter = toolRateLimiter;
    }

    public String run(String systemPrompt, String userMessage, Map<String, Tool> tools,
                      Mode mode, String label, String sessionId, RunSink sink) throws Exception {
        return run(systemPrompt, (Object) userMessage, tools, mode, label, sessionId, sink);
    }

    /** Run with a possibly-multimodal user content (a String, or an OpenAI parts array for images). */
    public String run(String systemPrompt, Object userContent, Map<String, Tool> tools,
                      Mode mode, String label, String sessionId, RunSink sink) throws Exception {
        RunSink s = sink == null ? RunSink.NOOP : sink;
        boolean mainTurn = "main".equals(label) && userContent instanceof String;
        String promptStr = mainTurn ? (String) userContent : null;

        // userPromptSubmit hooks: may block the whole turn or inject extra context before the prompt.
        String injected = null;
        if (mainTurn && hooks.hasPromptHooks()) {
            HookService.PromptResult pr = hooks.runUserPromptSubmit(promptStr);
            if (pr.blocked()) {
                s.log("[hook:userPromptSubmit] blocked turn");
                return pr.message();
            }
            if (pr.injectedContext() != null && !pr.injectedContext().isBlank()) {
                injected = pr.injectedContext();
                s.log("[hook:userPromptSubmit] injected context (" + injected.length() + " chars)");
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt));
        if (injected != null) {
            messages.add(msg("system", "<hook-context>\n" + injected + "\n</hook-context>"));
        }
        messages.add(msgObj("user", userContent));
        String answer = converse(messages, tools, mode, label, sessionId, s).answer();

        // stop hooks: fire when the turn finishes; their stdout is appended to the final answer.
        if (mainTurn && hooks.hasStopHooks()) {
            String stopOut = hooks.runStop(promptStr, answer);
            if (stopOut != null && !stopOut.isBlank()) {
                s.log("[hook:stop] appended " + stopOut.length() + " chars");
                answer = answer + "\n[stop-hook]\n" + stopOut;
            }
        }
        return answer;
    }

    public AgentResult converse(List<Map<String, Object>> startingMessages, Map<String, Tool> tools,
                                Mode mode, String label, String sessionId, RunSink sink) throws Exception {

        if (sink == null) sink = RunSink.NOOP;
        // The role governing this run, captured on the current thread (request thread, or the effective role
        // propagated from a parent run). Used to scope tool calls, including in delegated sub-agent runs.
        final String callerRole = capabilities.currentRole();
        // The tenant (caller identity) for this run, captured on the current thread; used for per-tool
        // rate limiting so one tenant's tool usage can't exhaust another's budget.
        final String callerTenant = RequestContext.current().user();
        List<Map<String, Object>> messages = new ArrayList<>(startingMessages);
        List<Map<String, Object>> specs = specsFor(tools);
        boolean interactive = "main".equals(label); // only the main loop honors interrupt/steer

        long deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L;
        Map<String, Integer> callCounts = new HashMap<>();
        List<String> plan = new ArrayList<>();
        int dupStrikes = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (System.nanoTime() > deadline) {
                boolean extend = false;
                if (interactive && "ask".equalsIgnoreCase(deadlineAction)) {
                    SessionContext.Ctx prevCtx = SessionContext.get();
                    SessionContext.set(new SessionContext.Ctx(sessionId, sink));
                    try {
                        extend = permissions.confirmContinue(sessionId, deadlineSeconds);
                    } finally {
                        SessionContext.set(prevCtx);
                    }
                }
                if (extend) {
                    deadline = System.nanoTime() + deadlineSeconds * 1_000_000_000L;
                    sink.log("[deadline:" + label + "] extended by " + deadlineSeconds + "s by the user.");
                } else {
                    sink.log("[guard:" + label + "] time budget of " + deadlineSeconds + "s reached; stopping.");
                    return new AgentResult("[stopped: reached the " + deadlineSeconds + "s time budget.]"
                            + planSuffix(plan), messages);
                }
            }

            if (interactive) {
                for (String s : interrupt.drainSteer(sessionId)) {
                    messages.add(msg("user", "[steering from user] " + s));
                    sink.log("[steer:" + label + "] injected: " + s);
                }
                if (interrupt.consumeStop(sessionId)) {
                    sink.log("[interrupt:" + label + "] stopped before model call.");
                    return new AgentResult("[stopped: interrupted by the user]" + planSuffix(plan), messages);
                }
            }

            messages = context.compactIfNeeded(messages, label, sink);

            final String sid = sessionId;
            BooleanSupplier cancel = interactive ? () -> interrupt.isStopRequested(sid) : () -> false;
            Map<String, Object> assistant;
            if (stream) {
                sink.log("[" + label + " thinking]");
                final RunSink s = sink;
                assistant = llama.chatStream(messages, specs, s::token, cancel);
            } else {
                assistant = llama.chat(messages, specs);
            }
            messages.add(assistant);
            metrics.inc("model_calls");
            Object ac = assistant.get("content");
            if (ac != null) metrics.addModelOutput(String.valueOf(ac).length());

            if (interactive && interrupt.consumeStop(sessionId)) {
                sink.log("[interrupt:" + label + "] stopped during generation.");
                return new AgentResult("[stopped: interrupted by the user mid-response]" + planSuffix(plan), messages);
            }

            List<Map<String, Object>> toolCalls = extractToolCalls(assistant);
            if (toolCalls.isEmpty()) {
                String answer = contentOf(assistant);
                if (answer == null || answer.isBlank()) answer = "[model returned no text]";
                return new AgentResult(answer + planSuffix(plan), messages);
            }

            List<CallInfo> infos = new ArrayList<>();
            for (Map<String, Object> call : toolCalls) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fn = (Map<String, Object>) call.get("function");
                String name = String.valueOf(fn.get("name"));
                String id = call.get("id") == null ? null : String.valueOf(call.get("id"));
                Map<String, Object> args = parseArgs(fn.get("arguments"));
                infos.add(new CallInfo(name, id, args, tools.get(name)));
            }

            // Validate each call's arguments against its tool schema BEFORE executing. A failure
            // becomes a corrective tool result the model can react to (recover) rather than a bad run.
            Map<CallInfo, String> validationErrors = new IdentityHashMap<>();
            for (CallInfo ci : infos) {
                if (ci.tool != null) {
                    String verr = SchemaValidator.validate(ci.name, ci.tool.parameters, ci.args);
                    if (verr != null) validationErrors.put(ci, verr);
                }
            }

            Map<CallInfo, Future<String>> futures = new IdentityHashMap<>();
            if (parallelTools) {
                final RunSink s = sink;
                for (CallInfo ci : infos) {
                    if (ci.tool != null && !ci.tool.mutating && validationErrors.get(ci) == null
                            && capabilities.permitsCurrent(ci.name)
                            && toolRateLimiter.allow(callerTenant, ci.name)) {
                        futures.put(ci, pool.submit(() -> runTool(callerRole, sid, s, ci.name, ci.tool, ci.args)));
                    }
                }
            }
            boolean parallelNote = parallelTools && futures.size() > 1;

            for (CallInfo ci : infos) {
                String result;
                if (ci.tool == null) {
                    result = "ERROR: unknown tool '" + ci.name + "'. Use only the provided tools.";
                } else if (validationErrors.get(ci) != null) {
                    result = validationErrors.get(ci);
                    sink.log("[" + label + ":invalid] " + ci.name + " " + ci.args);
                } else if (!capabilities.permitsCurrent(ci.name)) {
                    // Tool-level capability scoping: this caller's role is not allowed to use this tool.
                    result = "DENIED: tool '" + ci.name + "' is outside this caller's capability scope.";
                    sink.log("[" + label + ":capability] denied " + ci.name);
                    capabilities.auditDenial(ci.name);
                } else if (futures.get(ci) == null && !toolRateLimiter.allow(callerTenant, ci.name)) {
                    // Per-tool rate limit: this tenant has called this tool too often in the window.
                    // (Skipped when a future already exists — that tool was allowed at pre-submission.)
                    long retryAfter = toolRateLimiter.retryAfterSeconds(callerTenant, ci.name);
                    result = "RATE_LIMITED: tool '" + ci.name + "' exceeded its per-tenant rate limit; "
                            + "retry after ~" + retryAfter + "s.";
                    sink.log("[" + label + ":ratelimit] throttled " + ci.name + " (retry after ~" + retryAfter + "s)");
                    capabilities.auditToolRateLimited(ci.name);
                } else if (!ci.tool.mutating) {
                    sink.log("[" + label + ":tool] " + ci.name + " " + ci.args + (parallelNote ? " (parallel)" : ""));
                    Future<String> f = futures.get(ci);
                    result = (f != null) ? join(f) : runTool(callerRole, sessionId, sink, ci.name, ci.tool, ci.args);
                } else {
                    String signature = ci.name + "|" + ci.args;
                    int count = callCounts.merge(signature, 1, Integer::sum);
                    if (count > 2) {
                        dupStrikes++;
                        result = "NOTE: you already called '" + ci.name + "' with these exact arguments "
                                + (count - 1) + " time(s). Do NOT call it again; answer with what you have.";
                        sink.log("[guard:" + label + "] suppressed duplicate call to " + ci.name);
                    } else {
                        Decision d;
                        SessionContext.Ctx prevCtx = SessionContext.get();
                        SessionContext.set(new SessionContext.Ctx(sessionId, sink));
                        try {
                            d = permissions.decide(sessionId, ci.name, true, ci.args, mode);
                        } finally {
                            SessionContext.set(prevCtx);
                        }
                        switch (d.kind()) {
                            case ALLOW -> {
                                sink.log("[" + label + ":tool] " + ci.name + " " + ci.args);
                                result = runTool(callerRole, sessionId, sink, ci.name, ci.tool, ci.args);
                            }
                            case DENY -> result = "DENIED: " + d.note() + ".";
                            case RECORD_PLAN -> {
                                String desc = ci.name + " " + ci.args;
                                plan.add(desc);
                                sink.log("[" + label + ":plan] would run " + desc);
                                result = "[plan mode] Recorded (not executed): " + desc
                                        + ". Continue planning; do not assume it ran.";
                            }
                            default -> result = "DENIED.";
                        }
                    }
                }

                // trim, then fence untrusted (web / MCP) content against prompt injection
                ContextManager.Condensed condensed = context.condenseToolResultTraced(result);
                String finalResult = condensed.text();
                if (condensed.folded()) {
                    sink.log("[fold:" + label + "] condensed a large " + ci.name + " result: "
                            + condensed.originalChars() + " -> " + condensed.resultChars() + " chars");
                }
                if (ci.tool != null && ci.tool.untrusted) {
                    finalResult = Untrusted.wrap(ci.name, finalResult);
                }
                messages.add(toolResult(ci.id, ci.name, finalResult));
            }

            if (dupStrikes >= MAX_DUP_STRIKES) {
                sink.log("[guard:" + label + "] too many repeated calls; stopping.");
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

    /** Publishes sessionId + sink to the tool executor (via SessionContext), runs hooks + the tool. */
    private String runTool(String role, String sessionId, RunSink sink, String name, Tool tool, Map<String, Object> args) {
        SessionContext.Ctx prev = SessionContext.get();
        SessionContext.set(new SessionContext.Ctx(sessionId, sink));
        String prevRole = capabilities.effectiveRoleRaw();
        capabilities.setEffectiveRole(role); // propagate caller's role onto this (possibly pool) thread
        try {
            metrics.incTool(name);
            String block = hooks.runPre(name, args);
            if (block != null) {
                sink.log("[hook:pre] blocked " + name);
                return block;
            }
            if (extensions != null) extensions.emit(LoopEvent.preTool(sessionId, name, args));
            String result = safeExec(tool, args);
            if (result != null && (result.startsWith("ERROR") || result.startsWith("DENIED")
                    || result.startsWith("INVALID_ARGS") || result.startsWith("BLOCKED"))) {
                metrics.inc("tool_errors");
            }
            String postOut = hooks.runPost(name, args, result);
            if (postOut != null && !postOut.isBlank()) {
                result = result + "\n[post-hook]\n" + postOut;
            }
            if (extensions != null) extensions.emit(LoopEvent.postTool(sessionId, name, args, result));
            recorder.record(sessionId, name, tool.mutating, args, result);
            return result;
        } finally {
            capabilities.setEffectiveRole(prevRole);
            SessionContext.set(prev);
        }
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

    /** The enforced prompt cap (delegates to the budget service via the client). For plan-fallback. */
    public int promptCap() { return llama.promptCap(); }

    /** Measured token count of an assembled message list (real /tokenize, estimate fallback). */
    public int countPromptTokens(List<Map<String, Object>> messages) {
        int real = llama.countMessagesTokens(messages);
        return real >= 0 ? real : TokenBudget.estimateMessages(messages);
    }

    /** A one-message-pair starting prompt ([system,user]) for a quick pre-send budget check. */
    public List<Map<String, Object>> firstTurn(String systemPrompt, String userMessage) {
        List<Map<String, Object>> m = new ArrayList<>();
        m.add(msg("system", systemPrompt));
        m.add(msg("user", userMessage));
        return m;
    }

    private Map<String, Object> msg(String role, String content) {
        return msgObj(role, content);
    }

    private Map<String, Object> msgObj(String role, Object content) {
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
