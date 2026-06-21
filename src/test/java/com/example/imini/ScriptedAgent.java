package com.example.imini;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Shared test fixture for model-free, end-to-end golden traces that drive the REAL {@link AgentEngine}.
 *
 * <p>Extracted so {@code FakeModelHarnessTest}, {@code GoldenTraceWorkflowTest}, and
 * {@code RecoveryTraceTest} all script the model and build the engine the same way (no drift). The
 * scripted {@link ScriptedLlama} returns canned assistant messages (tool calls + a final answer); the
 * {@link #buildEngine} helper wires the real engine with real collaborators and compaction disabled (so
 * no live summary-model HTTP call). A {@link RecordingPermissions} captures each permission decision.
 */
final class ScriptedAgent {

    private ScriptedAgent() {}

    static String systemPrompt() { return "You are a test agent."; }

    /** An assistant turn that calls one tool. {@code arguments} is a Map (the engine accepts it directly). */
    static Map<String, Object> call(String name, Map<String, Object> args) {
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("arguments", args);
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "call_" + name);
        c.put("type", "function");
        c.put("function", fn);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", new ArrayList<>(List.of(c)));
        return assistant;
    }

    /** An assistant turn that is the final text answer (no tool calls). */
    static Map<String, Object> answer(String text) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("role", "assistant");
        a.put("content", text);
        return a;
    }

    /** A scripted, model-free {@link LlamaClient}: returns the next canned assistant message per call. */
    static class ScriptedLlama extends LlamaClient {
        private final List<Map<String, Object>> steps;
        private int next;
        String lastUserContent;
        List<Map<String, Object>> lastMessages;

        @SafeVarargs
        ScriptedLlama(Map<String, Object>... steps) {
            super(null);
            this.steps = new ArrayList<>(List.of(steps));
        }

        ScriptedLlama(List<Map<String, Object>> steps) {
            super(null);
            this.steps = new ArrayList<>(steps);
        }

        /** No-arg for subclasses that override {@link #nextStep} with their own step source. */
        protected ScriptedLlama() {
            super(null);
            this.steps = new ArrayList<>();
        }

        /** The next scripted assistant message for this turn. Default: sequential from the step list. */
        protected Map<String, Object> nextStep(List<Map<String, Object>> messages) {
            if (next >= steps.size()) return answer("[no more scripted steps]");
            return steps.get(next++);
        }

        /** The system-prompt text of the current turn (messages[0] when role=system), or "". */
        protected static String systemOf(List<Map<String, Object>> messages) {
            if (messages != null && !messages.isEmpty()) {
                Map<String, Object> m0 = messages.get(0);
                if ("system".equals(m0.get("role")) && m0.get("content") != null) {
                    return String.valueOf(m0.get("content"));
                }
            }
            return "";
        }

        private Map<String, Object> pop(List<Map<String, Object>> messages) {
            lastMessages = messages;
            for (Map<String, Object> m : messages) {
                if ("user".equals(m.get("role")) && m.get("content") != null) {
                    lastUserContent = String.valueOf(m.get("content"));
                }
            }
            return nextStep(messages);
        }

        @Override
        public Map<String, Object> chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
            return pop(messages);
        }

        @Override
        public Map<String, Object> chatStream(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                              Consumer<String> onToken, BooleanSupplier cancel) {
            return pop(messages);
        }

        /** Contents of every tool-result message the engine fed back (INVALID_ARGS, dup NOTE, tool output). */
        List<String> toolResults() {
            List<String> out = new ArrayList<>();
            if (lastMessages != null) {
                for (Map<String, Object> m : lastMessages) {
                    if ("tool".equals(m.get("role")) && m.get("content") != null) {
                        out.add(String.valueOf(m.get("content")));
                    }
                }
            }
            return out;
        }
    }

    /**
     * A scripted model that serves TWO (or more) agents on one engine by routing each turn to a script
     * chosen by a marker substring in the turn's system prompt — so a parent turn and a nested sub-agent
     * turn (which run on the same engine instance) each follow their own canned sequence regardless of
     * interleaving. Used by the subagent hand-off trace.
     */
    static final class RoutingScriptedLlama extends ScriptedLlama {
        private final Map<String, List<Map<String, Object>>> scripts;
        private final Map<String, Integer> idx = new java.util.HashMap<>();
        private final Map<String, List<Map<String, Object>>> lastByKey = new java.util.HashMap<>();

        RoutingScriptedLlama(Map<String, List<Map<String, Object>>> scripts) {
            super();
            this.scripts = scripts;
        }

        @Override
        protected Map<String, Object> nextStep(List<Map<String, Object>> messages) {
            String sys = systemOf(messages);
            for (Map.Entry<String, List<Map<String, Object>>> e : scripts.entrySet()) {
                if (sys.contains(e.getKey())) {
                    lastByKey.put(e.getKey(), messages);   // remember this agent's transcript
                    int i = idx.getOrDefault(e.getKey(), 0);
                    List<Map<String, Object>> script = e.getValue();
                    if (i >= script.size()) return answer("[no more scripted steps for " + e.getKey() + "]");
                    idx.put(e.getKey(), i + 1);
                    return script.get(i);
                }
            }
            return answer("[no script matched system prompt]");
        }

        /** Tool-result contents the agent matching {@code key} most recently saw (its own sub-transcript). */
        List<String> toolResultsFor(String key) {
            List<String> out = new ArrayList<>();
            List<Map<String, Object>> msgs = lastByKey.get(key);
            if (msgs != null) {
                for (Map<String, Object> m : msgs) {
                    if ("tool".equals(m.get("role")) && m.get("content") != null) {
                        out.add(String.valueOf(m.get("content")));
                    }
                }
            }
            return out;
        }
    }

    /** PermissionService that records each decision so a trace can assert the gate fired and its kind. */
    static final class RecordingPermissions extends PermissionService {
        final List<String> decisions = new ArrayList<>();
        RecordingPermissions(Approvals a, GitInspector g, HookService h) { super(a, g, h); }
        @Override
        public Decision decide(String sessionId, String tool, boolean mutating, Map args, Mode mode) {
            Decision d = super.decide(sessionId, tool, mutating, args, mode);
            decisions.add(tool + "=" + d.kind());
            return d;
        }
    }

    /** Build the real {@link AgentEngine} with real collaborators; compaction disabled (no live model). */
    static AgentEngine buildEngine(LlamaClient model, PermissionService perms, HookService hooks,
                                   GitInspector git) throws Exception {
        Database db = new Database();
        CapabilityService caps = new CapabilityService(new AuditLog(db));
        ToolRateLimiter rate = new ToolRateLimiter(db);
        return buildEngine(model, perms, hooks, git, caps, rate);
    }

    /**
     * As {@link #buildEngine(LlamaClient, PermissionService, HookService, GitInspector)} but with a
     * caller-supplied {@link CapabilityService} and {@link ToolRateLimiter} (already configured + init'd),
     * so access-control traces can exercise capability scoping and per-tenant rate limiting.
     */
    static AgentEngine buildEngine(LlamaClient model, PermissionService perms, HookService hooks,
                                   GitInspector git, CapabilityService caps, ToolRateLimiter rate) throws Exception {
        Database db = new Database();
        Metrics metrics = new Metrics(new RunService(), new RunHistoryStore(db));
        RunRecorder recorder = new RunRecorder(new AuditLog(db), new SessionStore(db), db);
        ContextManager ctx = new ContextManager(model, metrics);
        setField(ctx, ContextManager.class, "maxToolChars", 100000);
        setField(ctx, ContextManager.class, "foldEnabled", false);
        setField(ctx, ContextManager.class, "threshold", 100_000_000); // never compact in tests
        setField(ctx, ContextManager.class, "keepRecent", 50);
        InterruptService interrupt = new InterruptService();
        AgentEngine engine = new AgentEngine(model, ctx, perms, interrupt, hooks, metrics, recorder, caps, rate);
        setField(engine, AgentEngine.class, "deadlineSeconds", 120);
        return engine;
    }

    static void setField(Object target, Class<?> cls, String name, Object value) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ---- schema builders (shared by the trace tests) ----

    /** A JSON-schema property of the given type, e.g. {@code prop("string")}. */
    static Map<String, Object> prop(String type) {
        return Map.of("type", type);
    }

    /** An object JSON-schema with the given properties and required field names. */
    static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.of(required));
        return s;
    }

    // ---- real-engine harness rooted at a temp dir (shared by the trace tests) ----

    /**
     * A real {@link AgentEngine} plus the collaborators a trace usually needs to assert against: the
     * recording {@link RecordingPermissions}, the {@link GitInspector}, and the {@link Sandbox} rooted at
     * the test's temp dir. Built by {@link #harness}.
     */
    static final class Harness {
        final AgentEngine engine;
        final RecordingPermissions perms;
        final GitInspector git;
        final Sandbox sandbox;
        final HookService hooks;

        Harness(AgentEngine engine, RecordingPermissions perms, GitInspector git, Sandbox sandbox, HookService hooks) {
            this.engine = engine;
            this.perms = perms;
            this.git = git;
            this.sandbox = sandbox;
            this.hooks = hooks;
        }
    }

    /** Harness with default (disabled) capability + rate-limit services. */
    static Harness harness(LlamaClient model, Path dir) throws Exception {
        Database db = new Database();
        CapabilityService caps = new CapabilityService(new AuditLog(db));
        ToolRateLimiter rate = new ToolRateLimiter(db);
        return harness(model, dir, caps, rate);
    }

    /** Harness with caller-supplied (configured) capability + rate-limit services. */
    static Harness harness(LlamaClient model, Path dir, CapabilityService caps, ToolRateLimiter rate) throws Exception {
        Sandbox sandbox = new Sandbox();
        setField(sandbox, Sandbox.class, "root", dir);
        setField(sandbox, Sandbox.class, "confineWrites", false);
        GitInspector git = new GitInspector(sandbox);
        setField(git, GitInspector.class, "timeoutSeconds", 30);
        HookService hooks = new HookService();
        RecordingPermissions perms = new RecordingPermissions(new Approvals(), git, hooks);
        AgentEngine engine = buildEngine(model, perms, hooks, git, caps, rate);
        return new Harness(engine, perms, git, sandbox, hooks);
    }
}
