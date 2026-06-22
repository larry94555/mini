package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end approval flow for a commit: a real repo with a staged change, driven through
 * {@link PermissionService} in "remote" prompt mode, asserting (a) the parked approval payload carries the
 * staged diff (so the reviewer sees what will land) and (b) resolving it ALLOW lets the commit proceed.
 * Self-skips when git is unavailable.
 */
class GitCommitApprovalFlowTest {

    @Test
    void commitApprovalShowsStagedDiffThenCommits() throws Exception {
        if (!IntegrationGate.proceed("git", "GitCommitApprovalFlowTest.commitApprovalShowsStagedDiff", gitAvailable())) return;
        Path repo = Files.createTempDirectory("imini-approve-");
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "t@t");
        git(repo, "config", "user.name", "t");
        Files.writeString(repo.resolve("hello.txt"), "first\n");
        git(repo, "add", "-A");
        git(repo, "commit", "-qm", "feat: init");
        // make a new change and stage it -> there is a staged diff to surface
        Files.writeString(repo.resolve("hello.txt"), "first\nsecond\n");
        git(repo, "add", "-A");

        Sandbox sandbox = new Sandbox();
        setField(sandbox, Sandbox.class, "root", repo);

        GitInspector git = new GitInspector(sandbox);
        setField(git, GitInspector.class, "timeoutSeconds", 30);
        // sanity: there really is a staged diff
        assertTrue(!git.diffCachedStat().isBlank(), "precondition: a staged diff exists");

        Approvals approvals = new Approvals();
        HookService hooks = new HookService();                 // no hooks.json -> notification is a no-op
        PermissionService perms = new PermissionService(approvals, git, hooks);
        setField(perms, PermissionService.class, "promptMode", "remote");
        setField(perms, PermissionService.class, "approvalTimeoutSeconds", 30);
        setField(perms, PermissionService.class, "approvalTimeoutAction", "deny");
        setField(perms, PermissionService.class, "confine", false);
        setField(perms, PermissionService.class, "autoApprove", false);

        String session = "sess-approve";
        Map<String, Object> args = Map.of("message", "feat: add second line");

        // decide() blocks in await() until we resolve it -> run on a background thread
        AtomicReference<PermissionService.Decision> decision = new AtomicReference<>();
        Thread t = new Thread(() ->
                decision.set(perms.decide(session, "git_commit", true, args, PermissionService.Mode.ASK)));
        t.setDaemon(true);
        t.start();

        // wait for the approval to be parked, then inspect its payload
        List<Map<String, Object>> pending = waitForPending(approvals, session);
        assertEquals(1, pending.size(), "one approval should be pending");
        String payload = String.valueOf(pending.get(0).get("args"));
        assertTrue(payload.contains("_staged_diff"), "approval payload carries the staged diff: " + payload);
        assertTrue(payload.contains("hello.txt"), "staged diff names the changed file: " + payload);
        assertTrue(payload.contains("feat: add second line"), "approval payload keeps the commit message");

        // approve it -> decide returns ALLOW
        String id = String.valueOf(pending.get(0).get("id"));
        assertTrue(approvals.resolve(id, "allow"), "resolve should succeed");
        t.join(5000);
        assertEquals(PermissionService.Kind.ALLOW, decision.get().kind(), "approved commit is allowed");

        // and the commit itself works once allowed
        GitWriteTools gw = new GitWriteTools(sandbox);
        setField(gw, GitWriteTools.class, "toolTimeoutSeconds", 30);
        Tool commit = gw.all().stream().filter(x -> x.name.equals("git_commit")).findFirst().orElseThrow();
        String out = commit.executor.apply(args);
        assertTrue(out.startsWith("Committed") && out.contains("add second line"), "commit result: " + out);
    }

    // ---- helpers ----

    private static List<Map<String, Object>> waitForPending(Approvals approvals, String session) throws Exception {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> p = approvals.list(session);
            if (!p.isEmpty()) return p;
            Thread.sleep(20);
        }
        return approvals.list(session);
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
    }

    private static void setField(Object target, Class<?> cls, String name, Object value) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
