package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Companion to {@link SubAgentHandoffTraceTest}: asserts that failures INSIDE a subagent surface cleanly as
 * the {@code delegate_agent} tool result and never crash the parent loop. Two failure modes are covered: a
 * sub tool that throws (handled as an {@code ERROR:} result inside the sub loop), and a sub that trips its
 * OWN duplicate-call guard (the engine's stop string). Both run fully offline through the real engine.
 */
class SubAgentFailureTraceTest {

    private static final String PARENT_SYS = "PARENT-AGENT. You can delegate to a subagent.";
    private static final String SUB_SYS = "SUB-AGENT. You investigate and answer concisely.";

    @Test
    void failingSubToolSurfacesAsErrorAndParentRecovers() throws Exception {
        Path dir = Files.createTempDirectory("imini-subfail-");
        AtomicInteger subToolCalls = new AtomicInteger();

        Map<String, List<Map<String, Object>>> scripts = new LinkedHashMap<>();
        scripts.put("PARENT-AGENT", new ArrayList<>(List.of(
                call("delegate_agent", Map.of("name", "researcher", "task", "look it up")),
                answer("PARENT_OK: the subagent failed gracefully; informing the user."))));
        scripts.put("SUB-AGENT", new ArrayList<>(List.of(
                call("sub_lookup", Map.of("q", "thing")),                 // this tool throws
                answer("SUB_FAILED: the backend was unavailable, no data."))));
        ScriptedAgent.RoutingScriptedLlama model = new ScriptedAgent.RoutingScriptedLlama(scripts);

        SubAgent subAgent = engineAndSub(model, dir);
        Map<String, Tool> subTools = Map.of("sub_lookup",
                new Tool("sub_lookup", "Look something up (throws).",
                        ScriptedAgent.schema(Map.of("q", ScriptedAgent.prop("string")), "q"), false, a -> {
                    subToolCalls.incrementAndGet();
                    throw new RuntimeException("backend unavailable");
                }));
        Tool delegate = delegateTool(subAgent, subTools);

        String answerText = parentEngine.run(PARENT_SYS, "Look it up.",
                Map.of("delegate_agent", delegate), PermissionService.Mode.AUTO, "main", "pf-1", RunSink.NOOP);

        // the sub tool ran and threw; safeExec turned it into an ERROR result the sub saw
        assertEquals(1, subToolCalls.get(), "the sub tool was invoked");
        assertTrue(model.toolResultsFor("SUB-AGENT").stream().anyMatch(r -> r.startsWith("ERROR:")
                        && r.contains("backend unavailable")),
                "the sub saw the tool error as a fed-back result: " + model.toolResultsFor("SUB-AGENT"));
        // the sub's final answer propagated to the parent as the delegate result; parent did not crash
        assertTrue(model.toolResultsFor("PARENT-AGENT").stream().anyMatch(r -> r.contains("SUB_FAILED")),
                "the sub's answer is the delegate result in the parent: " + model.toolResultsFor("PARENT-AGENT"));
        assertTrue(answerText.contains("PARENT_OK"), "parent produced its own final answer: " + answerText);
    }

    @Test
    void subDuplicateGuardStopStringSurfacesToParent() throws Exception {
        Path dir = Files.createTempDirectory("imini-subdup-");
        AtomicInteger subWrites = new AtomicInteger();
        Path marker = dir.resolve("m.txt");

        // The sub repeats the SAME mutating call; its own duplicate-call guard stops the sub run, and that
        // engine-generated stop string becomes the delegate result.
        List<Map<String, Object>> subScript = new ArrayList<>();
        for (int i = 0; i < 5; i++) subScript.add(call("sub_write", Map.of("text", "x")));
        subScript.add(answer("(sub should have been stopped before this)"));

        Map<String, List<Map<String, Object>>> scripts = new LinkedHashMap<>();
        scripts.put("PARENT-AGENT", new ArrayList<>(List.of(
                call("delegate_agent", Map.of("name", "writer", "task", "write x")),
                answer("PARENT_OK: subagent stopped itself; relaying that."))));
        scripts.put("SUB-AGENT", subScript);
        ScriptedAgent.RoutingScriptedLlama model = new ScriptedAgent.RoutingScriptedLlama(scripts);

        SubAgent subAgent = engineAndSub(model, dir);
        Map<String, Tool> subTools = Map.of("sub_write",
                new Tool("sub_write", "Write (mutating).",
                        ScriptedAgent.schema(Map.of("text", ScriptedAgent.prop("string")), "text"), true, a -> {
                    subWrites.incrementAndGet();
                    try { Files.writeString(marker, String.valueOf(a.get("text"))); } catch (Exception ignore) {}
                    return "wrote";
                }));
        Tool delegate = delegateTool(subAgent, subTools);

        String answerText = parentEngine.run(PARENT_SYS, "Write x.",
                Map.of("delegate_agent", delegate), PermissionService.Mode.AUTO, "main", "pd-1", RunSink.NOOP);

        // the sub's own guard capped execution at two, and its stop string came back as the delegate result
        assertEquals(2, subWrites.get(), "the sub's duplicate guard caps its execution at two");
        assertTrue(model.toolResultsFor("PARENT-AGENT").stream().anyMatch(r -> r.contains("kept repeating the same tool call")),
                "the sub's stop string is the delegate result: " + model.toolResultsFor("PARENT-AGENT"));
        assertTrue(answerText.contains("PARENT_OK"), "parent produced its own final answer: " + answerText);
    }

    // ---- helpers ----

    private AgentEngine parentEngine;

    /** Build one real engine (captured into {@link #parentEngine}) and a real SubAgent wrapping it. */
    private SubAgent engineAndSub(LlamaClient model, Path dir) throws Exception {
        ScriptedAgent.Harness h = ScriptedAgent.harness(model, dir);
        this.parentEngine = h.engine;
        Database db = new Database();
        BuiltinTools builtins = new BuiltinTools(new CheckpointStore(db), new TodoStore(), h.sandbox,
                new RetrievalService(db), new PreviewStore());
        return new SubAgent(parentEngine, builtins);
    }

    private Tool delegateTool(SubAgent subAgent, Map<String, Tool> subTools) {
        return new Tool("delegate_agent",
                "Delegate to a named subagent; returns its final answer.",
                ScriptedAgent.schema(Map.of("name", ScriptedAgent.prop("string"), "task", ScriptedAgent.prop("string")), "name", "task"),
                false, a -> {
            try {
                return subAgent.run("sub-session", SUB_SYS, String.valueOf(a.get("task")), subTools, RunSink.NOOP);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

}
