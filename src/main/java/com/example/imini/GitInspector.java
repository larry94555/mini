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

    private String runGit(List<String> gitArgs) {
        ExecutorService ex = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(gitArgs);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(sandbox.root().toFile());
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
