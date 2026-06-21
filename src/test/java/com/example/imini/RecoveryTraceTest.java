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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden traces through the REAL {@link AgentEngine} for the engine's non-happy-path branches:
 * a mutation denied in PLAN mode (RECORD_PLAN, nothing executed), an invalid-args call that becomes
 * corrective feedback then a successful retry, and a repeated identical mutating call that trips the
 * duplicate-call guard. Model-free (scripted), so they run fully offline.
 */
class RecoveryTraceTest {

    /** A mutating tool that records how many times it actually executed (to prove non-execution in PLAN). */
    private static Tool countingWrite(AtomicInteger execs, Path target) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string", "description", "text to write"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("text"));
        return new Tool("write_marker", "Write text to the marker file (mutating).", schema, true, args -> {
            execs.incrementAndGet();
            try {
                Files.writeString(target, String.valueOf(args.get("text")));
                return "wrote " + target.getFileName();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    @Test
    void planModeRecordsButDoesNotExecute() throws Exception {
        Path dir = Files.createTempDirectory("imini-plan-");
        Path marker = dir.resolve("marker.txt");
        AtomicInteger execs = new AtomicInteger();

        Map<String, Tool> tools = Map.of("write_marker", countingWrite(execs, marker));
        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("write_marker", Map.of("text", "hello")),
                answer("I planned the write."));

        AgentEngine engine = engineFor(model, dir);
        String answerText = engine.run(ScriptedAgent.systemPrompt(), "Write hello to the marker.",
                tools, PermissionService.Mode.PLAN, "main", "plan-1", RunSink.NOOP);

        // RECORD_PLAN: the mutating tool was NOT executed, no file written
        assertEquals(0, execs.get(), "PLAN mode must not execute the mutating tool");
        assertFalse(Files.exists(marker), "no file should be written in PLAN mode");
        // the permission decision was RECORD_PLAN
        assertTrue(perms.decisions.contains("write_marker=RECORD_PLAN"), "decisions=" + perms.decisions);
        // the tool result told the model nothing ran, and the answer carries the plan suffix
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("[plan mode] Recorded (not executed)")),
                "tool result: " + model.toolResults());
        assertTrue(answerText.contains("PLAN MODE - nothing was executed"), "answer: " + answerText);
        assertTrue(answerText.contains("write_marker"), "plan lists the proposed call: " + answerText);
    }

    @Test
    void invalidArgsBecomeFeedbackThenRecover() throws Exception {
        Path dir = Files.createTempDirectory("imini-recover-");
        Path marker = dir.resolve("marker.txt");
        AtomicInteger execs = new AtomicInteger();

        Map<String, Tool> tools = Map.of("write_marker", countingWrite(execs, marker));
        // first call omits the required 'text' -> INVALID_ARGS; model recovers with a valid call.
        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("write_marker", Map.of()),                       // invalid: missing required 'text'
                call("write_marker", Map.of("text", "recovered")),    // valid retry
                answer("Recovered after the validation error."));

        AgentEngine engine = engineFor(model, dir);
        String answerText = engine.run(ScriptedAgent.systemPrompt(), "Write to the marker.",
                tools, PermissionService.Mode.AUTO, "recover-1", "recover-1", RunSink.NOOP);

        // the first call never executed (validation short-circuits before execution)
        assertEquals(1, execs.get(), "only the valid retry should execute");
        assertEquals("recovered", Files.readString(marker));
        // a corrective INVALID_ARGS message was fed back
        assertTrue(model.toolResults().stream().anyMatch(r -> r.startsWith("INVALID_ARGS")
                        && r.contains("missing required field 'text'")),
                "tool results: " + model.toolResults());
        assertTrue(answerText.contains("Recovered"), "final answer: " + answerText);
    }

    @Test
    void duplicateCallGuardStopsRepetition() throws Exception {
        Path dir = Files.createTempDirectory("imini-dup-");
        Path marker = dir.resolve("marker.txt");
        AtomicInteger execs = new AtomicInteger();

        Map<String, Tool> tools = Map.of("write_marker", countingWrite(execs, marker));
        // same mutating call, repeated: counts 1,2 execute; 3rd+ are suppressed by the guard; after
        // MAX_DUP_STRIKES (3) the run stops. Provide enough repeats to reach the stop.
        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("write_marker", Map.of("text", "x")),
                call("write_marker", Map.of("text", "x")),
                call("write_marker", Map.of("text", "x")),
                call("write_marker", Map.of("text", "x")),
                call("write_marker", Map.of("text", "x")),
                answer("(should not be reached)"));

        AgentEngine engine = engineFor(model, dir);
        String answerText = engine.run(ScriptedAgent.systemPrompt(), "Write x repeatedly.",
                tools, PermissionService.Mode.AUTO, "dup-1", "dup-1", RunSink.NOOP);

        // the guard NOTE was emitted (count > 2) and execution was capped at the first two calls
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("you already called 'write_marker'")),
                "guard NOTE expected in: " + model.toolResults());
        assertEquals(2, execs.get(), "the guard caps execution at the first two identical calls");
        // the run stopped on repeated calls rather than reaching the scripted answer
        assertTrue(answerText.contains("kept repeating the same tool call"),
                "run should stop on the duplicate guard: " + answerText);
    }

    // ---- helpers ----

    private RecordingPermsHolder perms;

    private static final class RecordingPermsHolder {
        final List<String> decisions;
        RecordingPermsHolder(List<String> d) { this.decisions = d; }
    }

    /** Build a real engine rooted at dir with a recording PermissionService captured into {@link #perms}. */
    private AgentEngine engineFor(LlamaClient model, Path dir) throws Exception {
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", dir);
        ScriptedAgent.setField(sandbox, Sandbox.class, "confineWrites", false);
        GitInspector git = new GitInspector(sandbox);
        HookService hooks = new HookService();
        ScriptedAgent.RecordingPermissions rp = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);
        this.perms = new RecordingPermsHolder(rp.decisions);
        return ScriptedAgent.buildEngine(model, rp, hooks, git);
    }
}
