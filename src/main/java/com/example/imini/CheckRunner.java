package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a plan step's declared {@code CHECK:} command to verify the step actually succeeded, so a
 * step's outcome is evidence-based rather than just the model's self-report. The command goes through
 * the SAME {@link Sandbox} screening as run_command (off / deny-only / allowlist) and runs in the
 * workspace root with a short timeout; success is exit code 0.
 */
@Component
public class CheckRunner {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckRunner.class);

    private final Sandbox sandbox;

    @Value("${agent.plan.check-timeout-seconds:20}")
    private int checkTimeoutSeconds;

    public CheckRunner(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Planner.CheckResult run(String command) {
        if (command == null || command.isBlank()) return new Planner.CheckResult(false, "no command");
        String denied = sandbox.screenCommand(command);
        if (denied != null) return new Planner.CheckResult(false, "denied: " + denied);
        try {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = new ProcessBuilder(sandbox.buildProcess(command, win));
            pb.directory(sandbox.root().toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "check-reader");
                t.setDaemon(true);
                return t;
            });
            Future<String> outF = ex.submit(() -> new String(proc.getInputStream().readAllBytes()));
            boolean done = proc.waitFor(checkTimeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                ex.shutdownNow();
                return new Planner.CheckResult(false, "timed out after " + checkTimeoutSeconds + "s");
            }
            int code = proc.exitValue();
            String out = outF.get(5, TimeUnit.SECONDS);
            ex.shutdown();
            String detail = "exit " + code + (out.isBlank() ? "" : "; " + out.strip());
            return new Planner.CheckResult(code == 0, truncate(detail, 300));
        } catch (Exception e) {
            log.debug("[check] error running '" + command + "': " + e.getMessage());
            return new Planner.CheckResult(false, "error: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
