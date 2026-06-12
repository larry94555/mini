package com.example.imini;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Containment policy for the dangerous tools: {@code run_command} and the file tools.
 *
 * <p>This class has three jobs:
 *
 * <ol>
 *   <li><strong>Command screening</strong> for {@code run_command}: off, deny-only, or allowlist.
 *   <li><strong>Path confinement</strong> for file reads/writes under the workspace root.
 *   <li><strong>Optional container exec</strong> by wrapping a command in a configured container/jail
 *       command.
 * </ol>
 *
 * <p>The pure decision logic is static so it can be unit-tested without Spring.
 */
@Component
public class Sandbox {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Sandbox.class);

  /** Obviously destructive patterns blocked in deny-only and allowlist modes. Not exhaustive. */
  public static final List<String> DEFAULT_DENY =
      List.of(
          "rm -rf /",
          "rm -rf /*",
          "rm -rf ~",
          ":(){",
          "fork()",
          "mkfs",
          "dd if=",
          "shutdown",
          "reboot",
          "halt",
          "> /dev/sd",
          "of=/dev/sd",
          "format ",
          "del /f",
          "del /q",
          "rmdir /s",
          "rd /s",
          "diskpart",
          "curl | sh",
          "wget | sh",
          "| sh",
          "| bash",
          "chmod -r 777 /",
          "chown -r");

  @Value("${agent.workspace-root:}")
  private String workspaceRootCfg;

  @Value("${agent.confine-to-workspace:true}")
  private boolean confineWrites;

  @Value("${sandbox.confine-reads:true}")
  private boolean confineReads;

  @Value("${sandbox.command-mode:deny-only}")
  private String commandMode;

  @Value("${sandbox.allow:}")
  private String allowCfg;

  @Value("${sandbox.deny:}")
  private String denyCfg;

  @Value("${sandbox.max-command-length:2000}")
  private int maxCommandLength;

  @Value("${sandbox.container-command:}")
  private String containerCommand;

  private Path root;
  private final List<String> allow = new ArrayList<>();
  private final List<String> deny = new ArrayList<>(DEFAULT_DENY);

  /** The resolved workspace root: absolute and normalized. */
  public Path root() {
    return root;
  }

  @PostConstruct
  public void load() {
    root =
        (workspaceRootCfg == null || workspaceRootCfg.isBlank()
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(workspaceRootCfg))
            .toAbsolutePath()
            .normalize();

    addCsv(allow, allowCfg);
    addCsv(deny, denyCfg);

    log.info(
        "[sandbox] command-mode="
            + commandMode
            + (containerCommand != null && !containerCommand.isBlank() ? " (container exec on)" : "")
            + "; reads confined="
            + confineReads
            + "; root="
            + root);
  }

  private void addCsv(List<String> target, String csv) {
    if (csv == null || csv.isBlank()) {
      return;
    }

    for (String s : csv.split(",")) {
      if (!s.isBlank()) {
        target.add(s.trim());
      }
    }
  }

  /** Returns null if a path is allowed for this tool, else a denial message. */
  public String enforcePath(String tool, String path, boolean isWrite) {
    boolean active = isWrite ? confineWrites : confineReads;
    if (!active || path == null || path.isBlank()) {
      return null;
    }

    if (!PermissionService.isWithin(root, path)) {
      return "DENIED: '" + path + "' is outside the workspace (" + root + ").";
    }

    return null;
  }

  /** Returns null if the command may run, else a denial reason without the {@code DENIED:} prefix. */
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
      parts.add(cmd); // appended as one argument to the in-container "sh -c"
      return parts;
    }

    return windows ? List.of("cmd.exe", "/c", cmd) : List.of("sh", "-c", cmd);
  }

  /** Pure, unit-testable command-screening logic. */
  public static String screen(
      String cmd, String mode, List<String> allow, List<String> deny, int maxLen) {
    if (mode == null || "off".equalsIgnoreCase(mode)) {
      return null;
    }
    if (cmd == null || cmd.isBlank()) {
      return "empty command";
    }
    if (maxLen > 0 && cmd.length() > maxLen) {
      return "command exceeds max length (" + maxLen + ")";
    }

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
          if (a == null || a.isBlank()) {
            continue;
          }
          String trimmed = a.trim();
          if (trimmed.equalsIgnoreCase(first) || cmd.trim().startsWith(trimmed)) {
            ok = true;
            break;
          }
        }
      }
      if (!ok) {
        return "not in the command allowlist";
      }
    }

    return null;
  }

  public static String firstWord(String cmd) {
    String trimmed = cmd == null ? "" : cmd.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    return trimmed.split("\\s+")[0];
  }
}
