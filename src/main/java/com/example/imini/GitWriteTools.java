package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Mutating git tools that complete the edit -> verify -> COMMIT loop: {@code git_stage},
 * {@code git_commit}, and {@code git_branch}. They are the write-side counterpart to the read-only
 * {@code git_status}/{@code git_diff} navigation tools in {@link CodebaseTools} and the
 * {@link GitInspector}.
 *
 * <p>All three are {@code mutating=true}, so they go through {@link PermissionService} / the approval
 * flow exactly like {@code write_file} and {@code run_command} — nothing is committed without the
 * same gate that guards any other mutation. The recommended workflow is: review the staged diff with
 * {@code git_diff staged=true} (or this class's commit-time diff echo), draft a message with the
 * bundled {@code commit-message} skill, then {@code git_commit}.
 *
 * <p>The argv builders are static and pure so they can be unit-tested without a live repo; the
 * shell-out mirrors {@link CodebaseTools}'s {@code runGit} (workspace-rooted, timed out, combined
 * stdout/stderr). Path arguments are confined to the workspace via {@link Sandbox#enforcePath}.
 */
@Component
public class GitWriteTools {

    private final Sandbox sandbox;
    @Value("${agent.tool-timeout-seconds:60}") private int toolTimeoutSeconds;

    public GitWriteTools(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public List<Tool> all() {
        return List.of(gitStage(), gitCommit(), gitBranch());
    }

    // ---------------------------------------------------------------------
    // Tool definitions (all mutating -> permission/approval flow)
    // ---------------------------------------------------------------------

    public Tool gitStage() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("paths", strArrayProp("Paths to stage (workspace-relative). Omit or pass [\".\"] to stage all changes."));
        return new Tool("git_stage",
                "Stage changes for the next commit (git add). Pass specific 'paths' or omit to stage everything. "
                        + "Mutating: requires approval. Review first with git_diff, then git_commit.",
                schema(props), true, args -> {
            List<String> paths = strList(args, "paths");
            for (String p : paths) {
                if (!p.equals(".")) {
                    String denied = sandbox.enforcePath("git_stage", p, true);
                    if (denied != null) return denied;
                }
            }
            String out = runGit(stageArgs(paths));
            if (out.startsWith("ERROR")) return out;
            // Echo what is now staged so the model (and the approval UI) can see the result.
            String staged = runGit(List.of("diff", "--cached", "--stat"));
            return "Staged.\n" + (staged.isBlank() ? "(nothing staged — already committed or no changes)" : truncate(staged, 4000));
        });
    }

    public Tool gitCommit() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("message", strProp("Commit message. Use the commit-message skill to draft a Conventional-Commits message from the staged diff."));
        props.put("all", boolProp("Stage all tracked-file modifications first (git commit -a), then commit (default false)."));
        return new Tool("git_commit",
                "Create a git commit from the staged changes with the given 'message'. Set all=true to also stage "
                        + "tracked-file changes first (git commit -a). Mutating: requires approval. Draft the message "
                        + "with the commit-message skill, and review the staged diff (git_diff staged=true) before committing.",
                schema(props, "message"), true, args -> {
            String message = str(args, "message");
            if (message.isBlank()) return "ERROR: provide a non-empty 'message' (use the commit-message skill to draft one).";
            boolean all = boolArg(args, "all", false);
            // Guard: nothing to commit (unless -a will pick up tracked changes).
            String staged = runGit(List.of("diff", "--cached", "--name-only"));
            if (staged.isBlank() && !all) {
                return "ERROR: nothing staged to commit. Use git_stage first, or pass all=true to commit tracked changes.";
            }
            String out = runGit(commitArgs(message, all));
            if (out.startsWith("ERROR")) return out;
            // Confirm with the new HEAD line.
            String head = runGit(List.of("log", "-1", "--pretty=format:%h %s"));
            return "Committed.\n" + truncate(out, 4000) + (head.isBlank() ? "" : "\nHEAD now: " + head.trim());
        });
    }

    public Tool gitBranch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", strProp("Branch name. Omit to list branches (current marked with '*')."));
        props.put("create", boolProp("Create the branch and switch to it (git checkout -b). Default false (switch to an existing branch)."));
        return new Tool("git_branch",
                "List branches (omit 'name'), switch to an existing branch ('name'), or create+switch to a new one "
                        + "(name + create=true). Mutating: requires approval.",
                schema(props), true, args -> {
            String name = str(args, "name");
            if (name.isBlank()) {
                String out = runGit(List.of("branch", "--list"));
                return out.isBlank() ? "(no branches)" : truncate(out, 4000);
            }
            if (!isValidBranchName(name)) return "ERROR: invalid branch name '" + name + "'.";
            boolean create = boolArg(args, "create", false);
            String out = runGit(branchArgs(name, create));
            if (out.startsWith("ERROR")) return out;
            return (create ? "Created and switched to branch '" : "Switched to branch '") + name + "'.\n" + truncate(out, 2000);
        });
    }

    // ---------------------------------------------------------------------
    // Pure argv builders (static -> unit-testable without a repo)
    // ---------------------------------------------------------------------

    /** git argv for staging. Empty/["."] -> {@code git add -A}; else {@code git add -- <paths>}. */
    public static List<String> stageArgs(List<String> paths) {
        List<String> a = new ArrayList<>(List.of("add"));
        boolean all = paths == null || paths.isEmpty() || (paths.size() == 1 && ".".equals(paths.get(0)));
        if (all) {
            a.add("-A");
        } else {
            a.add("--");
            a.addAll(paths);
        }
        return a;
    }

    /** git argv for committing. {@code git commit [-a] -m <message>}. */
    public static List<String> commitArgs(String message, boolean all) {
        List<String> a = new ArrayList<>(List.of("commit"));
        if (all) a.add("-a");
        a.add("-m");
        a.add(message == null ? "" : message);
        return a;
    }

    /** git argv for branch switch/create. create -> {@code git checkout -b <name>}; else {@code git checkout <name>}. */
    public static List<String> branchArgs(String name, boolean create) {
        List<String> a = new ArrayList<>(List.of("checkout"));
        if (create) a.add("-b");
        a.add(name);
        return a;
    }

    /** Conservative branch-name validation (no whitespace, no shell/ref metacharacters, not a flag). */
    public static boolean isValidBranchName(String name) {
        if (name == null || name.isBlank()) return false;
        if (name.startsWith("-")) return false;                 // not a flag
        if (name.contains("..") || name.endsWith("/") || name.endsWith(".lock")) return false;
        return name.matches("[A-Za-z0-9._/-]+");
    }

    // ---------------------------------------------------------------------
    // Shell-out (mirrors CodebaseTools.runGit: workspace-rooted, timed, combined stdout/stderr)
    // ---------------------------------------------------------------------

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
                Thread t = new Thread(r, "git-write");
                t.setDaemon(true);
                return t;
            });
            Future<String> outF = ex.submit(() ->
                    new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            boolean done = proc.waitFor(toolTimeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                ex.shutdownNow();
                return "ERROR: git timed out after " + toolTimeoutSeconds + "s.";
            }
            String out = outF.get(5, TimeUnit.SECONDS);
            int code = proc.exitValue();
            ex.shutdown();
            if (code != 0) {
                return "ERROR: git exited " + code + (out == null || out.isBlank() ? "" : ": " + out.trim())
                        + " (is git installed and is the workspace a git repo with something to do?)";
            }
            return out == null ? "" : out;
        } catch (Exception e) {
            if (ex != null) ex.shutdownNow();
            return "ERROR: " + e.getMessage() + " (is git installed and is the workspace a git repo?)";
        }
    }

    // ---------------------------------------------------------------------
    // Schema/arg helpers (local copies, matching CodebaseTools' style)
    // ---------------------------------------------------------------------

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        if (required.length > 0) s.put("required", List.of(required));
        return s;
    }

    private static Map<String, Object> strProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> boolProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "boolean");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> strArrayProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "array");
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        p.put("items", items);
        p.put("description", description);
        return p;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean dflt) {
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? dflt : Boolean.parseBoolean(String.valueOf(v).trim().toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }
}
