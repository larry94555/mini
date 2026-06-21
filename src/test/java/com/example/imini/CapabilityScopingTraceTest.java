package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces for the engine's ACCESS-CONTROL branches, driven through the real {@link AgentEngine}
 * with a scripted (model-free) model: capability scoping (a tool outside the caller's role scope is
 * denied + audited, not executed) and per-tenant rate limiting (a tool over its limit returns
 * RATE_LIMITED). Reuses the shared {@link ScriptedAgent} fixture. Runs fully offline.
 */
class CapabilityScopingTraceTest {

    /** A read-only tool that records how many times it executed. */
    private static Tool countingRead(AtomicInteger execs) {
        return new Tool("read_marker", "Read (non-mutating).",
                schema(Map.of("path", prop("string")), "path"), false, args -> {
            execs.incrementAndGet();
            return "read ok";
        });
    }

    /** A mutating tool that records how many times it executed. */
    private static Tool countingWrite(AtomicInteger execs, Path target) {
        return new Tool("write_marker", "Write (mutating).",
                schema(Map.of("text", prop("string")), "text"), true, args -> {
            execs.incrementAndGet();
            try { Files.writeString(target, String.valueOf(args.get("text"))); return "wrote"; }
            catch (Exception e) { return "ERROR: " + e.getMessage(); }
        });
    }

    @Test
    void toolOutsideRoleScopeIsDeniedAndNotExecuted() throws Exception {
        Path dir = Files.createTempDirectory("imini-cap-");
        Path marker = dir.resolve("marker.txt");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();

        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("read_marker", countingRead(reads));
        tools.put("write_marker", countingWrite(writes, marker));

        // role "reader" may only read_marker; write_marker is out of scope.
        RecordingCapabilities caps = new RecordingCapabilities(new AuditLog(new Database()));
        ScriptedAgent.setField(caps, CapabilityService.class, "enabled", true);
        ScriptedAgent.setField(caps, CapabilityService.class, "scopesCfg", "reader=read_marker");
        ScriptedAgent.setField(caps, CapabilityService.class, "defaultScopeCfg", "*");
        caps.init();
        caps.setEffectiveRole("reader"); // the caller's role for this run (same thread as dispatch)

        ToolRateLimiter rate = new ToolRateLimiter(new Database());
        rate.init(); // disabled by default -> allow all

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("read_marker", Map.of("path", "x")),    // in scope -> runs
                call("write_marker", Map.of("text", "nope")), // out of scope -> denied
                answer("Did what I was allowed to."));

        String answerText = engine(model, dir, caps, rate)
                .run(ScriptedAgent.systemPrompt(), "Read, then try to write.",
                        tools, PermissionService.Mode.AUTO, "cap-1", "cap-1", RunSink.NOOP);

        // in-scope tool ran; out-of-scope tool was denied with the scope message and never executed
        assertEquals(1, reads.get(), "the in-scope read should run");
        assertEquals(0, writes.get(), "the out-of-scope write must not execute");
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("outside this caller's capability scope")),
                "denial message expected: " + model.toolResults());
        assertTrue(caps.audited.contains("write_marker"), "the denial was audited: " + caps.audited);
        assertTrue(answerText.contains("allowed"), "final answer: " + answerText);
    }

    @Test
    void toolOverRateLimitReturnsRateLimited() throws Exception {
        Path dir = Files.createTempDirectory("imini-rate-");
        AtomicInteger reads = new AtomicInteger();
        Map<String, Tool> tools = Map.of("read_marker", countingRead(reads));

        CapabilityService caps = new CapabilityService(new AuditLog(new Database()));
        caps.init(); // disabled -> permit all

        // read_marker is limited to 1 call / 60s; in-memory (no DB) so it's deterministic.
        ToolRateLimiter rate = new ToolRateLimiter(new Database());
        ScriptedAgent.setField(rate, ToolRateLimiter.class, "enabled", true);
        ScriptedAgent.setField(rate, ToolRateLimiter.class, "limitsCfg", "read_marker=1/60");
        ScriptedAgent.setField(rate, ToolRateLimiter.class, "persistent", false);
        rate.init();

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("read_marker", Map.of("path", "x")),  // allowed (1st within window)
                call("read_marker", Map.of("path", "x")),  // over limit -> RATE_LIMITED
                answer("Stopped after the rate limit."));

        String answerText = engine(model, dir, caps, rate)
                .run(ScriptedAgent.systemPrompt(), "Read twice quickly.",
                        tools, PermissionService.Mode.AUTO, "rate-1", "rate-1", RunSink.NOOP);

        assertEquals(1, reads.get(), "only the first call executes; the second is throttled before running");
        assertTrue(model.toolResults().stream().anyMatch(r -> r.startsWith("RATE_LIMITED")
                        && r.contains("read_marker")),
                "RATE_LIMITED message expected: " + model.toolResults());
        assertTrue(answerText.contains("Stopped"), "final answer: " + answerText);
    }

    // ---- helpers ----

    private AgentEngine engine(LlamaClient model, Path dir, CapabilityService caps, ToolRateLimiter rate)
            throws Exception {
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", dir);
        ScriptedAgent.setField(sandbox, Sandbox.class, "confineWrites", false);
        GitInspector git = new GitInspector(sandbox);
        HookService hooks = new HookService();
        ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);
        return ScriptedAgent.buildEngine(model, perms, hooks, git, caps, rate);
    }

    /** CapabilityService that records which tools were audited as denied. */
    private static final class RecordingCapabilities extends CapabilityService {
        final List<String> audited = new java.util.ArrayList<>();
        RecordingCapabilities(AuditLog audit) { super(audit); }
        @Override
        public void auditDenial(String tool) {
            audited.add(tool);
            super.auditDenial(tool);
        }
    }

    private static Map<String, Object> prop(String type) { return Map.of("type", type); }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.of(required));
        return s;
    }
}
