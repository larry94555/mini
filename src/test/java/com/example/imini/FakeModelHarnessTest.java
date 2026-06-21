package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic end-to-end harness tests that need no live llama-server: a scripted model emits tool
 * calls, the REAL {@link AgentEngine} validates and runs them, results go back into the transcript, and
 * the model eventually answers.
 *
 * <p>Now driven through the shared {@link ScriptedAgent} fixture (same scripted model + real engine the
 * golden traces use), so there is one harness, not two. The educational point is unchanged: read → edit →
 * answer, and a bad call becoming corrective feedback instead of executing.
 */
class FakeModelHarnessTest {

    @Test
    void fakeModelCanReadThenEditThenAnswer() throws Exception {
        Path dir = Files.createTempDirectory("imini-fake-");
        Path notes = dir.resolve("notes.txt");
        Files.writeString(notes, "status: draft\n");

        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("read_file", readFileTool());
        tools.put("edit_file", editFileTool());

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("read_file", Map.of("path", notes.toString())),
                call("edit_file", Map.of("path", notes.toString(),
                        "old_str", "status: draft", "new_str", "status: final")),
                answer("Updated notes.txt and verified the target text."));

        String answer = run(model, tools, dir, "Change notes.txt from draft to final.");

        assertEquals("status: final\n", Files.readString(notes));
        assertTrue(answer.contains("Updated notes.txt"));
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("status: draft")), "read result fed back");
        assertTrue(model.toolResults().stream().anyMatch(r -> r.startsWith("Edited")), "edit result fed back");
    }

    @Test
    void invalidArgsBecomeFeedbackInsteadOfToolExecution() throws Exception {
        Path dir = Files.createTempDirectory("imini-fake-");
        Path notes = dir.resolve("notes.txt");
        Files.writeString(notes, "status: draft\n");

        Map<String, Tool> tools = Map.of("read_file", readFileTool());

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("read_file", Map.of()),                          // invalid: missing path
                call("read_file", Map.of("path", notes.toString())),  // valid retry
                answer("Recovered after the harness reported the missing path."));

        String answer = run(model, tools, dir, "Read notes.txt, but first make a bad call.");

        assertTrue(answer.contains("Recovered"));
        assertTrue(model.toolResults().stream().anyMatch(r -> r.startsWith("INVALID_ARGS")), "INVALID_ARGS fed back");
        assertTrue(model.toolResults().stream().anyMatch(r -> r.contains("status: draft")), "valid read result fed back");
    }

    // ---- tiny real Tools (read-only + a fake editor), scoped to the temp dir ----

    private String run(LlamaClient model, Map<String, Tool> tools, Path dir, String prompt) throws Exception {
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", dir);
        GitInspector git = new GitInspector(sandbox);
        HookService hooks = new HookService();
        ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);
        AgentEngine engine = ScriptedAgent.buildEngine(model, perms, hooks, git);
        return engine.run(ScriptedAgent.systemPrompt(), prompt, tools, PermissionService.Mode.AUTO, "main", "fake-1", RunSink.NOOP);
    }

    private static Tool readFileTool() {
        return new Tool("read_file", "Read a file.", schema(Map.of("path", prop("string")), "path"),
                false, args -> {
            try { return Files.readString(Path.of(String.valueOf(args.get("path")))); }
            catch (Exception e) { return "ERROR: not found"; }
        });
    }

    private static Tool editFileTool() {
        return new Tool("edit_file", "Replace text in a file.",
                schema(Map.of("path", prop("string"), "old_str", prop("string"), "new_str", prop("string")),
                        "path", "old_str", "new_str"),
                true, args -> {
            try {
                Path p = Path.of(String.valueOf(args.get("path")));
                String content = Files.readString(p);
                String oldStr = String.valueOf(args.get("old_str"));
                if (!content.contains(oldStr)) return "ERROR: old_str not found";
                Files.writeString(p, content.replace(oldStr, String.valueOf(args.get("new_str"))));
                return "Edited " + p.getFileName();
            } catch (Exception e) { return "ERROR: " + e.getMessage(); }
        });
    }

    private static Map<String, Object> prop(String type) { return Map.of("type", type); }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        return schema;
    }
}
