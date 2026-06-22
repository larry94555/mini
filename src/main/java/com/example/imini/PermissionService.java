package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Permission policy for mutating tools.
 *
 * <p>This layer demonstrates the harness-side safety boundary:
 *
 * <ul>
 *   <li>Allow and deny rules loaded from {@code permissions.json}.
 *   <li>Per-session remembered decisions.
 *   <li>Workspace confinement for writes.
 *   <li>ASK, AUTO, and PLAN modes.
 * </ul>
 *
 * <p>Read-only tools are allowed and do not prompt.
 */
@Component
public class PermissionService {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PermissionService.class);

  public enum Mode {
    ASK,
    AUTO,
    PLAN
  }

  public enum Kind {
    ALLOW,
    DENY,
    RECORD_PLAN
  }

  public record Decision(Kind kind, String note) {}

  private static final Path CONFIG = Path.of("permissions.json");

  @Value("${agent.auto-approve:false}")
  private boolean autoApprove;

  @Value("${agent.confine-to-workspace:true}")
  private boolean confine;

  @Value("${agent.workspace-root:}")
  private String workspaceRootCfg;

  @Value("${permissions.prompt-mode:console}")
  private String promptMode;

  @Value("${permissions.approval-timeout-seconds:120}")
  private int approvalTimeoutSeconds;

  @Value("${permissions.approval-timeout-action:deny}")
  private String approvalTimeoutAction;

  private final Approvals approvals;
  private final GitInspector git;
  private final HookService hooks;
  private final ObjectMapper mapper = new ObjectMapper();
  private final Set<String> allow = new LinkedHashSet<>();
  private final Set<String> deny = new LinkedHashSet<>();
  private final Map<String, Set<String>> remembered = new ConcurrentHashMap<>();
  private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

  private Path root;

  /**
   * Tools that must always go through human approval — never auto-approved (even in {@code auto} mode) and
   * never satisfied by an allow rule. Granting/revoking a workspace root is a trust decision.
   */
  static final java.util.Set<String> ALWAYS_CONFIRM =
      java.util.Set.of("grant_workspace_root", "revoke_workspace_root");

  /**
   * Track B workspace-roots registry. Optional: injected in production, null when {@code PermissionService}
   * is constructed directly (tests), in which case write confinement falls back to the single {@link #root}.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private WorkspaceRoots workspaceRoots;

  public PermissionService(Approvals approvals, GitInspector git, HookService hooks) {
    this.approvals = approvals;
    this.git = git;
    this.hooks = hooks;
  }

  @PostConstruct
  @SuppressWarnings("unchecked")
  public void load() {
    root =
        (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg))
            .toAbsolutePath()
            .normalize();

    if (Files.exists(CONFIG)) {
      try {
        Map<String, Object> cfg = mapper.readValue(Files.readAllBytes(CONFIG), Map.class);
        addAll(allow, cfg.get("allow"));
        addAll(deny, cfg.get("deny"));
        log.info(
            "[permissions] loaded "
                + allow.size()
                + " allow / "
                + deny.size()
                + " deny rule(s) from permissions.json");
      } catch (Exception e) {
        log.warn("[permissions] could not read permissions.json: " + e.getMessage());
      }
    }

    log.info("[permissions] workspace root: " + root + (confine ? " (writes confined)" : ""));
  }

  private void addAll(Set<String> set, Object rules) {
    if (rules instanceof List<?> list) {
      for (Object rule : list) {
        set.add(String.valueOf(rule));
      }
    }
  }

  /** Decide whether a tool call may proceed. */
  public Decision decide(String sessionId, String tool, boolean mutating, Map args, Mode mode) {
    if (!mutating) {
      return new Decision(Kind.ALLOW, null);
    }
    if (matches(deny, tool, args)) {
      return new Decision(Kind.DENY, "blocked by a deny rule");
    }
    if (confine && writesOutsideRoot(tool, args)) {
      return new Decision(Kind.DENY, "target path is outside the workspace (" + root + ")");
    }
    // Always-confirm tools (e.g. granting a new workspace root) are a trust decision: they are NEVER
    // auto-approved and never satisfied by an allow rule. In plan mode they still record; otherwise they
    // route to the human approval path regardless of auto/ask. A deny rule above can still block them.
    if (ALWAYS_CONFIRM.contains(tool)) {
      if (mode == Mode.PLAN) {
        return new Decision(Kind.RECORD_PLAN, null);
      }
      return "remote".equalsIgnoreCase(promptMode)
          ? decideRemote(sessionId, tool, args)
          : promptConsole(sessionId, tool, args);
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
    if ("remote".equalsIgnoreCase(promptMode)) {
      return decideRemote(sessionId, tool, args);
    }
    return promptConsole(sessionId, tool, args);
  }

  private Decision decideRemote(String sessionId, String tool, Map args) {
    // Notify (attention: the agent is requesting approval) — fire-and-forget if configured.
    hooks.runNotification("Approval requested for tool '" + tool + "'", tool);

    // For a commit, surface the staged diff in the approval UI so the reviewer sees what will be committed.
    Map enriched = args;
    if (("git_commit".equals(tool) || "git_stage".equals(tool)) && git != null) {
      try {
        String staged = git.diffCachedStat();
        if (staged != null && !staged.isBlank()) {
          enriched = new java.util.LinkedHashMap<>(args);
          enriched.put("_staged_diff", staged.length() > 4000 ? staged.substring(0, 4000) : staged);
        }
      } catch (Exception ignore) {
        // best effort; never block approval on a diff failure
      }
    } else if ("create_project".equals(tool)) {
      // Summarize the manifest (root, file count, total bytes, tree) instead of dumping every file's content
      // into the approval UI.
      try {
        enriched = new java.util.LinkedHashMap<>(args);
        enriched.remove("files");
        enriched.put("_summary", ProjectTools.summarize(args));
      } catch (Exception ignore) {
        // best effort
      }
    }

    String argsJson;
    try {
      argsJson = mapper.writeValueAsString(enriched);
    } catch (Exception e) {
      argsJson = String.valueOf(enriched);
    }

    String decision =
        approvals.await(
            sessionId, tool, argsJson, approvalTimeoutSeconds * 1000L, approvalTimeoutAction);

    return switch (decision == null ? "deny" : decision) {
      case "always" -> {
        rememberedFor(sessionId).add(ruleKey(tool, args));
        yield new Decision(Kind.ALLOW, "approved (remembered)");
      }
      case "allow" -> new Decision(Kind.ALLOW, "approved");
      default -> new Decision(Kind.DENY, "not approved");
    };
  }

  private Decision promptConsole(String sessionId, String tool, Map args) {
    System.out.println("\n[permission] Tool '" + tool + "' wants to run with:");
    System.out.println("  " + args);
    System.out.print("[permission] Allow? (y = once, a = always, N = no): ");

    try {
      String line = in.readLine();
      String answer = line == null ? "" : line.trim().toLowerCase();
      if (answer.equals("a")) {
        rememberedFor(sessionId).add(ruleKey(tool, args));
        System.out.println("[permission] will always allow this from now on.");
        return new Decision(Kind.ALLOW, "remembered");
      }
      if (answer.equals("y")) {
        return new Decision(Kind.ALLOW, "approved once");
      }
      return new Decision(Kind.DENY, "the user declined");
    } catch (IOException e) {
      return new Decision(Kind.DENY, "no console input available");
    }
  }

  /**
   * Asked when a run hits its time budget. Returns false if no console or remote approval is
   * available, so a detached run still terminates.
   */
  public boolean confirmContinue(String sessionId, int seconds) {
    if ("remote".equalsIgnoreCase(promptMode)) {
      String decision =
          approvals.await(
              sessionId,
              "(continue run)",
              "used " + seconds + "s budget",
              approvalTimeoutSeconds * 1000L,
              approvalTimeoutAction);
      return "allow".equals(decision) || "always".equals(decision);
    }

    System.out.println("\n[deadline] This run has used its " + seconds + "s time budget.");
    System.out.print("[deadline] Continue for another " + seconds + "s? (y = yes, N = stop): ");
    try {
      String line = in.readLine();
      return line != null && line.trim().equalsIgnoreCase("y");
    } catch (IOException e) {
      return false;
    }
  }

  /** A rule matches a tool by exact name, or {@code run_command:} by command prefix. */
  private boolean matches(Set<String> rules, String tool, Map args) {
    if (rules.contains(tool)) {
      return true;
    }

    if (tool.equals("run_command")) {
      String command = String.valueOf(args.getOrDefault("command", "")).trim();
      for (String rule : rules) {
        if (rule.startsWith("run_command:")) {
          String prefix = rule.substring("run_command:".length()).trim();
          if (!prefix.isEmpty() && command.startsWith(prefix)) {
            return true;
          }
        }
      }
    }

    return false;
  }

  private Set<String> rememberedFor(String sessionId) {
    return remembered.computeIfAbsent(sessionId, key -> ConcurrentHashMap.newKeySet());
  }

  private String ruleKey(String tool, Map args) {
    if (tool.equals("run_command")) {
      String command = String.valueOf(args.getOrDefault("command", "")).trim();
      String first = command.isEmpty() ? "" : command.split("\\s+")[0];
      return "run_command:" + first;
    }
    return tool;
  }

  private boolean writesOutsideRoot(String tool, Map args) {
    if (!(tool.equals("write_file") || tool.equals("edit_file"))) {
      return false;
    }
    Object path = args.get("path");
    if (path == null) {
      return false;
    }
    if (workspaceRoots != null) {
      // Outside iff the path is not within any READ_WRITE root. (Being inside a granted root does NOT
      // auto-approve the write — it merely lets it proceed to the normal approval path below.)
      return !workspaceRoots.canWrite(String.valueOf(path));
    }
    return !isWithin(root, String.valueOf(path));
  }

  /** True if {@code candidate}, resolved against {@code root}, stays inside {@code root}. */
  public static boolean isWithin(Path root, String candidate) {
    try {
      Path target = root.resolve(candidate).normalize();
      return target.startsWith(root);
    } catch (Exception e) {
      return false;
    }
  }
}
