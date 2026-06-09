package com.example.imini;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Containment policy for the dangerous tools (run_command + the file tools). Three jobs:
 *
 *   1. COMMAND SCREENING for run_command:
 *        - off        : no screening (legacy behavior)
 *        - deny-only  : block a built-in denylist of obviously destructive patterns (+ your extras)
 *        - allowlist  : only run commands whose first word / prefix is on sandbox.allow (most locked)
 *      Plus a max command length.
 *   2. PATH CONFINEMENT extended to READS (read_file / view / list_dir), not just writes -- so the
 *      agent can't read /etc/passwd or wander above the workspace root.
 *   3. OPTIONAL CONTAINER EXEC: wrap a command to run inside a container/jail you specify, e.g.
 *        docker run --rm --network none -v {workdir}:/work -w /work alpine sh -c
 *      ({workdir} -> workspace root; the command is appended as a single argument).
 *
 * The pure decision logic is static (screen / firstWord) so it is unit-testable without Spring.
 */
@Component
public class Sandbox {

    /** Obviously-destructive patterns blocked in deny-only and allowlist modes. Not exhaustive. */
    public static final List<String> DEFAULT_DENY = List.of(
            "rm -rf /", "rm -rf /*", "rm -rf ~", ":(){", "fork()", "mkfs", "dd if=",
            "shutdown", "reboot", "halt", "> /dev/sd", "of=/dev/sd", "format ",
            "del /f", "del /q", "rmdir /s", "rd /s", "diskpart", "curl | sh", "wget | sh",
            "| sh", "| bash", "chmod -r 777 /", "chown -r");

    @Value("${agent.workspace-root:}") private String workspaceRootCfg;
    @Value("${agent.confine-to-workspace:true}") private boolean confineWrites;
    @Value("${sandbox.confine-reads:true}") private boolean confineReads;
    @Value("${sandbox.command-mode:deny-only}") private String commandMode;
    @Value("${sandbox.allow:}") private String allowCfg;
    @Value("${sandbox.deny:}") private String denyCfg;
    @Value("${sandbox.max-command-length:2000}") private int maxCommandLength;
    @Value("${sandbox.container-command:}") private String containerCommand;

    private Path root;
    private final List<String> allow = new ArrayList<>();
    private final List<String> deny = new ArrayList<>(DEFAULT_DENY);

    @PostConstruct
    public void load() {
        root = (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg)).toAbsolutePath().normalize();
        addCsv(allow, allowCfg);
        addCsv(deny, denyCfg);
        System.out.println("[sandbox] command-mode=" + commandMode
                + (containerCommand != null && !containerCommand.isBlank() ? " (container exec on)" : "")
                + "; reads confined=" + confineReads + "; root=" + root);
    }

    private void addCsv(List<String> target, String csv) {
        if (csv == null || csv.isBlank()) return;
        for (String s : csv.split(",")) if (!s.isBlank()) target.add(s.trim());
    }

    /** Returns null if a path is allowed for this tool, else a denial message. */
    public String enforcePath(String tool, String path, boolean isWrite) {
        boolean active = isWrite ? confineWrites : confineReads;
        if (!active || path == null || path.isBlank()) return null;
        if (!PermissionService.isWithin(root, path)) {
            return "DENIED: '" + path + "' is outside the workspace (" + root + ").";
        }
        return null;
    }

    /** Returns null if the command may run, else a denial reason (without the "DENIED: " prefix). */
    public String screenCommand(String cmd) {
        return screen(cmd, commandMode, allow, deny, maxCommandLength);
    }

    /** Builds the process argv for a command, applying optional container wrapping. */
    public List<String> buildProcess(String cmd, boolean windows) {
        if (containerCommand != null && !containerCommand.isBlank()) {
            List<String> parts = new ArrayList<>();
            for (String p : containerCommand.trim().split("\\s+")) {
                parts.add(p.replace("{workdir}", root.toString()));
            }
            parts.add(cmd); // appended as a single argument to the in-container "sh -c"
            return parts;
        }
        return windows ? List.of("cmd.exe", "/c", cmd) : List.of("sh", "-c", cmd);
    }

    // --- pure, unit-testable decision logic ---------------------------------

    public static String screen(String cmd, String mode, List<String> allow, List<String> deny, int maxLen) {
        if (mode == null || "off".equalsIgnoreCase(mode)) return null;
        if (cmd == null || cmd.isBlank()) return "empty command";
        if (maxLen > 0 && cmd.length() > maxLen) return "command exceeds max length (" + maxLen + ")";
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (deny != null) {
            for (String d : deny) {
                if (d != null && !d.isBlank() && lower.contains(d.toLowerCase(Locale.ROOT))) {
                    return "matches a denied pattern ('" + d.trim() + "')";
                }
            }
        }
        if ("allowlist".equalsIgnoreCase(mode)) {
            String first = firstWord(cmd);
            boolean ok = false;
            if (allow != null) {
                for (String a : allow) {
                    if (a == null || a.isBlank()) continue;
                    String t = a.trim();
                    if (t.equalsIgnoreCase(first) || cmd.trim().startsWith(t)) { ok = true; break; }
                }
            }
            if (!ok) return "not in the command allowlist";
        }
        return null;
    }

    public static String firstWord(String cmd) {
        String t = cmd == null ? "" : cmd.trim();
        if (t.isEmpty()) return "";
        String[] parts = t.split("\\s+");
        return parts[0];
    }
}
