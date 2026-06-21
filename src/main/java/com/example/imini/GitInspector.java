package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Read-only git inspection for edit-trust: {@code git status --porcelain} and {@code git diff --stat}
 * over the workspace root. Like the git_* navigation tools it shells out to git directly (read-only),
 * and it returns "" when git is missing or the workspace is not a repo, so callers degrade gracefully.
 */
@Component
public class GitInspector {

    private final Sandbox sandbox;

    @Value("${agent.verify-edits.timeout-seconds:15}")
    private int timeoutSeconds;

    public GitInspector(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public String status() {
        return runGit(List.of("status", "--porcelain=v1"));
    }

    public String diffStat() {
        return runGit(List.of("diff", "--stat"));
    }

    /** {@code git diff --cached --stat}: the staged changes, for surfacing in the commit approval prompt. */
    public String diffCachedStat() {
        return runGit(List.of("diff", "--cached", "--stat"));
    }

    /**
     * Snapshot the current working tree (tracked + untracked, honoring .gitignore) as a git tree object
     * and return its SHA, WITHOUT touching the user's index or working tree -- it stages into a throwaway
     * temporary index ({@code GIT_INDEX_FILE}). Returns "" if not a repo / git is missing. (It writes
     * loose blob/tree objects into .git/objects, which git garbage-collects; nothing user-visible
     * changes.) Two snapshots taken around a step can be diffed for that step's exact delta.
     */
    public String snapshotTree() {
        java.nio.file.Path idx = null;
        try {
            idx = java.nio.file.Files.createTempFile("imini-idx-", ".tmp");
            java.nio.file.Files.deleteIfExists(idx); // git creates the index file itself
            java.util.Map<String, String> env = java.util.Map.of("GIT_INDEX_FILE", idx.toString());
            runGit(List.of("add", "-A"), env);
            String tree = runGit(List.of("write-tree"), env).trim();
            return tree.matches("[0-9a-fA-F]{7,64}") ? tree : "";
        } catch (Exception e) {
            return "";
        } finally {
            try { if (idx != null) java.nio.file.Files.deleteIfExists(idx); } catch (Exception ignore) {}
        }
    }

    /** {@code git diff --stat from to} (or "" when either tree is blank / not a repo). */
    public String diffStatBetween(String fromTree, String toTree) {
        if (fromTree == null || fromTree.isBlank() || toTree == null || toTree.isBlank()) return "";
        return runGit(List.of("diff", "--stat", fromTree, toTree));
    }

    /** {@code git diff --name-only from to} (or "" when either tree is blank / not a repo). */
    public String diffNamesBetween(String fromTree, String toTree) {
        if (fromTree == null || fromTree.isBlank() || toTree == null || toTree.isBlank()) return "";
        return runGit(List.of("diff", "--name-only", fromTree, toTree));
    }

    private String runGit(List<String> gitArgs) {
        return runGit(gitArgs, java.util.Map.of());
    }

    private String runGit(List<String> gitArgs, java.util.Map<String, String> env) {
        ExecutorService ex = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(gitArgs);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(sandbox.root().toFile());
            if (env != null && !env.isEmpty()) pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ex = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "git-inspect");
                t.setDaemon(true);
                return t;
            });
            Future<String> outF = ex.submit(() ->
                    new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            boolean done = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                ex.shutdownNow();
                return "";
            }
            String out = outF.get(5, TimeUnit.SECONDS);
            ex.shutdown();
            return out == null ? "" : out;
        } catch (Exception e) {
            if (ex != null) ex.shutdownNow();
            return ""; // git missing or not a repo -> no edit-trust block
        }
    }
}
