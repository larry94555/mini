package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tier 2 permissions. The old y/N gate grows into a policy:
 *
 *   - allow / deny RULES loaded from permissions.json ("edit_file", or "run_command:git status").
 *     A deny rule always wins; an allow rule skips the prompt.
 *   - REMEMBERED decisions: answering "a" (always) at a prompt adds an allow rule for the rest of
 *     the run.
 *   - WORKSPACE CONFINEMENT: write_file / edit_file targeting a path outside the workspace root are
 *     denied outright, even in auto mode -- a hard safety boundary.
 *   - MODES: ASK (prompt per mutating call), AUTO (approve, still confined), PLAN (record the
 *     intended action and DON'T execute it, so the model produces a plan you can review).
 *
 * Read-only tools are always allowed and never prompt.
 */
@Component
public class PermissionService {

    public enum Mode { ASK, AUTO, PLAN }
    public enum Kind { ALLOW, DENY, RECORD_PLAN }
    public record Decision(Kind kind, String note) {}

    private static final Path CONFIG = Path.of("permissions.json");

    @Value("${agent.auto-approve:false}")
    private boolean autoApprove;
    @Value("${agent.confine-to-workspace:true}")
    private boolean confine;
    @Value("${agent.workspace-root:}")
    private String workspaceRootCfg;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> allow = new LinkedHashSet<>();
    private final Set<String> deny = new LinkedHashSet<>();
    private final Map<String, Set<String>> remembered = new ConcurrentHashMap<>();
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    private Path root;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        root = (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg)).toAbsolutePath().normalize();
        if (Files.exists(CONFIG)) {
            try {
                Map<String, Object> cfg = mapper.readValue(Files.readAllBytes(CONFIG), Map.class);
                addAll(allow, cfg.get("allow"));
                addAll(deny, cfg.get("deny"));
                System.out.println("[permissions] loaded " + allow.size() + " allow / "
                        + deny.size() + " deny rule(s) from permissions.json");
            } catch (Exception e) {
                System.out.println("[permissions] could not read permissions.json: " + e.getMessage());
            }
        }
        System.out.println("[permissions] workspace root: " + root + (confine ? " (writes confined)" : ""));
    }

    private void addAll(Set<String> set, Object rules) {
        if (rules instanceof List<?> l) for (Object r : l) set.add(String.valueOf(r));
    }

    /** Decide whether a (mutating) tool call may proceed. */
    public Decision decide(String sessionId, String tool, boolean mutating, Map<String, Object> args, Mode mode) {
        if (!mutating) return new Decision(Kind.ALLOW, null);

        if (matches(deny, tool, args)) {
            return new Decision(Kind.DENY, "blocked by a deny rule");
        }
        if (confine && writesOutsideRoot(tool, args)) {
            return new Decision(Kind.DENY, "target path is outside the workspace (" + root + ")");
        }
        if (matches(allow, tool, args) || matches(rememberedFor(sessionId), tool, args)) {
            return new Decision(Kind.ALLOW, "allowed by rule");
        }
        if (mode == Mode.PLAN) {
            return new Decision(Kind.RECORD_PLAN, null);
        }
        if (mode == Mode.AUTO || autoApprove) {
            return new Decision(Kind.ALLOW, "auto-approved");
        }
        return promptConsole(sessionId, tool, args);
    }

    private Decision promptConsole(String sessionId, String tool, Map<String, Object> args) {
        System.out.println("\n[permission] Tool '" + tool + "' wants to run with:");
        System.out.println("            " + args);
        System.out.print("[permission] Allow? (y = once, a = always, N = no): ");
        try {
            String line = in.readLine();
            String ans = line == null ? "" : line.trim().toLowerCase();
            if (ans.equals("a")) {
                rememberedFor(sessionId).add(ruleKey(tool, args));
                System.out.println("[permission] will always allow this from now on.");
                return new Decision(Kind.ALLOW, "remembered");
            }
            if (ans.equals("y")) return new Decision(Kind.ALLOW, "approved once");
            return new Decision(Kind.DENY, "the user declined");
        } catch (IOException e) {
            return new Decision(Kind.DENY, "no console input available");
        }
    }

    /**
     * Asked when a run hits its time budget: lets the user grant another window or let it stop.
     * Returns false (stop) if no console is attached, so a detached run still terminates.
     */
    public boolean confirmContinue(int seconds) {
        System.out.println("\n[deadline] This run has used its " + seconds + "s time budget.");
        System.out.print("[deadline] Continue for another " + seconds + "s? (y = yes, N = stop): ");
        try {
            String line = in.readLine();
            return line != null && line.trim().equalsIgnoreCase("y");
        } catch (IOException e) {
            return false;
        }
    }

    /** A rule matches a tool by exact name, or "run_command:<prefix>" by command prefix. */
    private boolean matches(Set<String> rules, String tool, Map<String, Object> args) {
        if (rules.contains(tool)) return true;
        if (tool.equals("run_command")) {
            String cmd = String.valueOf(args.getOrDefault("command", "")).trim();
            for (String r : rules) {
                if (r.startsWith("run_command:")) {
                    String prefix = r.substring("run_command:".length()).trim();
                    if (!prefix.isEmpty() && cmd.startsWith(prefix)) return true;
                }
            }
        }
        return false;
    }

    private Set<String> rememberedFor(String sessionId) {
        return remembered.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
    }

    private String ruleKey(String tool, Map<String, Object> args) {
        if (tool.equals("run_command")) {
            String cmd = String.valueOf(args.getOrDefault("command", "")).trim();
            String first = cmd.isEmpty() ? "" : cmd.split("\\s+")[0];
            return "run_command:" + first;
        }
        return tool;
    }

    private boolean writesOutsideRoot(String tool, Map<String, Object> args) {
        if (!(tool.equals("write_file") || tool.equals("edit_file"))) return false;
        Object p = args.get("path");
        if (p == null) return false;
        return !isWithin(root, String.valueOf(p));
    }

    /** True if {@code candidate}, resolved against {@code root}, stays inside {@code root}. */
    public static boolean isWithin(Path root, String candidate) {
        try {
            Path target = root.resolve(candidate).normalize();
            return target.startsWith(root);
        } catch (Exception e) {
            return false; // if we can't resolve it, treat as unsafe
        }
    }
}
