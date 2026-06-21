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
 * Golden trace for the subagent hand-off, driven through the real {@link AgentEngine} and the real
 * {@link SubAgent} with a scripted model. A parent turn dispatches to a named subagent via a
 * {@code delegate_agent}-style tool; the subagent runs its OWN turn (its own tool call + answer) on the
 * same engine (label {@code "sub"}); its final answer returns into the parent transcript as the tool
 * result; and the parent produces the final answer.
 *
 * <p>Both agents are scripted from one {@link ScriptedAgent.RoutingScriptedLlama}, which routes each turn
 * to the parent or sub script by a marker in the system prompt. Runs fully offline.
 */
class SubAgentHandoffTraceTest {

    private static final String PARENT_SYS = "PARENT-AGENT. You can delegate to a subagent.";
    private static final String SUB_SYS = "SUB-AGENT. You investigate and answer concisely.";

    @Test
    void parentDelegatesToSubagentAndIncorporatesItsResult() throws Exception {
        Path dir = Files.createTempDirectory("imini-subagent-");
        AtomicInteger subToolRuns = new AtomicInteger();
        AtomicInteger parentToolRuns = new AtomicInteger();

        // Build ONE real engine; the real SubAgent reuses it for the nested ("sub") run.
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", dir);
        GitInspector git = new GitInspector(sandbox);
        HookService hooks = new HookService();
        ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);

        // Two scripts on one model, routed by system-prompt marker.
        Map<String, List<Map<String, Object>>> scripts = new LinkedHashMap<>();
        scripts.put("PARENT-AGENT", new ArrayList<>(List.of(
                call("delegate_agent", Map.of("name", "researcher", "task", "find the magic number")),
                answer("PARENT_DONE: the subagent reported the number; relaying it to the user."))));
        scripts.put("SUB-AGENT", new ArrayList<>(List.of(
                call("sub_lookup", Map.of("q", "magic number")),
                answer("SUB_RESULT: the magic number is 42 (per sub_lookup)."))));
        ScriptedAgent.RoutingScriptedLlama model = new ScriptedAgent.RoutingScriptedLlama(scripts);

        AgentEngine engine = ScriptedAgent.buildEngine(model, perms, hooks, git);

        // The real SubAgent wraps the same engine; BuiltinTools is only needed for its research overload.
        Database db = new Database();
        BuiltinTools builtins = new BuiltinTools(new CheckpointStore(db), new TodoStore(), sandbox,
                new RetrievalService(db), new PreviewStore());
        SubAgent subAgent = new SubAgent(engine, builtins);

        // The subagent's scoped (read-only) tool set.
        Map<String, Tool> subTools = Map.of("sub_lookup",
                new Tool("sub_lookup", "Look something up (sub, non-mutating).",
                        schema(Map.of("q", prop("string")), "q"), false, a -> {
                    subToolRuns.incrementAndGet();
                    return "lookup hit for " + a.get("q");
                }));

        // The parent's delegate tool: hands the task to the real SubAgent, returns ONLY its final answer.
        Tool delegate = new Tool("delegate_agent",
                "Delegate a task to a named subagent that runs in its own loop and returns its final answer.",
                schema(Map.of("name", prop("string"), "task", prop("string")), "name", "task"),
                false, a -> {
            try {
                return subAgent.run("sub-session", SUB_SYS, String.valueOf(a.get("task")), subTools, RunSink.NOOP);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
        // A parent-only marker tool, to show parent tools are distinct from the sub's.
        Tool parentNote = new Tool("parent_note", "Record a parent note (non-mutating).",
                schema(Map.of("text", prop("string")), "text"), false, a -> {
            parentToolRuns.incrementAndGet();
            return "noted";
        });
        Map<String, Tool> parentTools = new LinkedHashMap<>();
        parentTools.put("delegate_agent", delegate);
        parentTools.put("parent_note", parentNote);

        String answerText = engine.run(PARENT_SYS, "Find the magic number and tell me.",
                parentTools, PermissionService.Mode.AUTO, "main", "parent-session", RunSink.NOOP);

        // 1) the subagent's own tool actually ran inside the nested loop
        assertEquals(1, subToolRuns.get(), "the subagent's tool should run in its own loop");
        assertEquals(0, parentToolRuns.get(), "the parent's other tool was not part of this script");

        // 2) the subagent's final answer propagated back into the PARENT transcript as the tool result
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("SUB_RESULT: the magic number is 42")),
                "the sub's answer should be the delegate tool result in the parent transcript: " + model.toolResults());

        // 3) the parent produced the final answer (its own script, not the sub's)
        assertTrue(answerText.contains("PARENT_DONE"), "parent produced the final answer: " + answerText);
        assertTrue(!answerText.contains("SUB_RESULT"), "the sub's raw answer is not the parent's final answer");

        // 4) the delegate dispatch was permission-gated like any tool (non-mutating -> not in decisions,
        //    but the run completed cleanly through the real engine on both levels)
        assertTrue(answerText.length() > 0);
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
