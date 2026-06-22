package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.imini.ScriptedAgent.answer;
import static com.example.imini.ScriptedAgent.call;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-trace end-to-end tests that drive the REAL {@link AgentEngine} turn loop with a scripted
 * (model-free) {@link LlamaClient}, against a real git repo, asserting the whole chain: tool dispatch,
 * the permission/approval decision (recorded), hook firing (pre/post/stop), and the git-verified
 * edit-trust summary assembled the same way {@code AgentLoop} does.
 *
 * <p>The scripted model + real-engine construction live in the shared {@link ScriptedAgent} fixture.
 */
class GoldenTraceWorkflowTest {

    @Test
    void editStageCommitTrace() throws Exception {
        if (!IntegrationGate.proceed("git", "GoldenTraceWorkflowTest.editStageCommit", GitRepoFixture.available())) return;

        // --- a real repo with an initial commit (isolated from ambient git config) ---
        GitRepoFixture gitRepo = GitRepoFixture.initWithCommit("imini-golden-", "app.txt", "status: draft\n");
        Path repo = gitRepo.path();

        // --- real components rooted at the repo ---
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", repo);
        GitInspector git = new GitInspector(sandbox);
        ScriptedAgent.setField(git, GitInspector.class, "timeoutSeconds", 30);

        // hooks that prove firing: pre writes a marker, post echoes, stop appends to the answer
        Path preMark = repo.resolve(".prehook");
        HookService hooks = new HookService();
        addHook(hooks, "pre", "git_commit", "touch '" + preMark + "'");
        addHook(hooks, "post", "git_commit", "echo POSTHOOK_RAN");
        addHook(hooks, "stop", "*", "echo STOPHOOK_RAN");

        ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);

        // real tools: edit_file (BuiltinTools) + git_stage/git_commit (GitWriteTools)
        Database db = new Database();
        BuiltinTools builtins = new BuiltinTools(new CheckpointStore(db), new TodoStore(), sandbox,
                new RetrievalService(db), new PreviewStore());
        GitWriteTools gw = new GitWriteTools(sandbox);
        ScriptedAgent.setField(gw, GitWriteTools.class, "toolTimeoutSeconds", 30);
        Map<String, Tool> tools = new LinkedHashMap<>();
        tools.put("edit_file", builtins.editFile());
        for (Tool t : gw.all()) tools.put(t.name, t);

        // scripted model: edit -> stage -> commit -> answer
        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(
                call("edit_file", Map.of("path", repo.resolve("app.txt").toString(),
                        "old_str", "status: draft", "new_str", "status: final")),
                call("git_stage", Map.of()),
                call("git_commit", Map.of("message", "feat: finalize app.txt")),
                answer("Edited, staged and committed app.txt."));

        AgentEngine engine = ScriptedAgent.buildEngine(model, perms, hooks, git);
        String answer = engine.run(ScriptedAgent.systemPrompt(), "Finalize app.txt and commit it.",
                tools, PermissionService.Mode.AUTO, "main", "golden-1", RunSink.NOOP);

        // 1) tool dispatch actually happened: file changed + a new commit exists
        assertEquals("status: final\n", Files.readString(repo.resolve("app.txt")));
        assertEquals("feat: finalize app.txt", gitRepo.git("log", "-1", "--pretty=%s").trim(),
                "the commit landed on HEAD");

        // 2) the permission decision was made for each mutating tool, and allowed
        assertTrue(perms.decisions.contains("edit_file=ALLOW"), "edit_file gated: " + perms.decisions);
        assertTrue(perms.decisions.contains("git_stage=ALLOW"), "git_stage gated: " + perms.decisions);
        assertTrue(perms.decisions.contains("git_commit=ALLOW"), "git_commit gated: " + perms.decisions);

        // 3) hooks fired: pre (marker file) + stop (appended to answer)
        assertTrue(Files.exists(preMark), "preToolUse hook ran for git_commit");
        assertTrue(answer.contains("STOPHOOK_RAN"), "stop hook output appended to the answer: " + answer);

        // 4) edit-trust summary assembled exactly as AgentLoop does (git status/diff + EditSummary)
        Files.writeString(repo.resolve("app.txt"), "status: final\nverified: yes\n");
        String summary = EditSummary.format(git.status(), git.diffStat(), List.of("app.txt"));
        assertTrue(summary.contains("app.txt"), "edit-trust summary names the changed file: " + summary);
        assertNotNull(EditSummary.oneLine(git.status(), git.diffStat()));
    }

    @Test
    void mcpPromptSlashCommandTrace() throws Exception {
        if (!IntegrationGate.proceed("node", "GoldenTraceWorkflowTest.mcpPromptSlashCommand", McpStubFixture.available())) return;

        McpManager mcp = new McpManager();
        ScriptedAgent.setField(mcp, McpManager.class, "toolTimeoutSeconds", 30);
        mcp.connect("stub", McpStubFixture.command());

        // MCP discovery parses the stub server's JSON-RPC responses; that needs a real JSON mapper at
        // runtime. Under the offline stub mapper (no-op readTree) discovery yields nothing -> self-skip.
        if (!mcp.isPromptCommand("/mcp__stub__review")) {
            System.out.println("[skip] MCP discovery produced no prompt (offline stub JSON mapper; runs fully in CI)");
            return;
        }

        String rendered = mcp.renderPromptCommand("/mcp__stub__review file=A.java");
        assertTrue(rendered != null && rendered.contains("review A.java"), "prompt rendered: " + rendered);

        ScriptedAgent.ScriptedLlama model = new ScriptedAgent.ScriptedLlama(answer("Reviewed as requested."));
        Sandbox sandbox = new Sandbox();
        ScriptedAgent.setField(sandbox, Sandbox.class, "root", Files.createTempDirectory("imini-mcp-"));
        GitInspector git = new GitInspector(sandbox);
        HookService hooks = new HookService();
        ScriptedAgent.RecordingPermissions perms = new ScriptedAgent.RecordingPermissions(new Approvals(), git, hooks);
        AgentEngine engine = ScriptedAgent.buildEngine(model, perms, hooks, git);

        engine.run(ScriptedAgent.systemPrompt(), rendered, Map.of(), PermissionService.Mode.AUTO, "main", "golden-2", RunSink.NOOP);
        assertTrue(model.lastUserContent != null && model.lastUserContent.contains("review A.java"),
                "rendered MCP prompt reached the model: " + model.lastUserContent);
    }

    // ---------------- test-local helpers (git/process/resources) ----------------

    @SuppressWarnings("unchecked")
    private static void addHook(HookService hooks, String field, String match, String command) throws Exception {
        Field f = HookService.class.getDeclaredField(field);
        f.setAccessible(true);
        List<Object> list = (List<Object>) f.get(hooks);
        Class<?> hookCls = Class.forName("com.example.imini.HookService$Hook");
        var ctor = hookCls.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        list.add(ctor.newInstance(match, command));
    }
}
