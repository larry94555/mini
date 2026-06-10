package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deterministic codebase-navigation tools -- the part of "understanding a repo" that should never go
 * through the model or the (fuzzy) retrieval index:
 *
 *   glob        find files by name pattern (e.g. "**​/*.java")
 *   grep        search file contents by regex, returning file:line: matches
 *   repo_tree   an indented directory tree (heavy dirs like .git/target/node_modules pruned)
 *   read_many   read several files in one call
 *   git_status  porcelain status of the workspace repo
 *   git_diff    unified diff (optionally --cached, optionally for one path)
 *
 * All are read-only (mutating=false) so the engine can run them in parallel, and all confine to the
 * workspace root via {@link Sandbox}. The walking/searching logic is static so it is unit-testable
 * against a temp directory without Spring. git_status/git_diff shell out to {@code git} (read-only
 * subcommands only) in the workspace root.
 */
@Component
public class CodebaseTools {

    /** Directories never worth walking for navigation. */
    public static final Set<String> IGNORE_DIRS = Set.of(
            ".git", "target", "build", "dist", "out", "bin", "node_modules",
            ".idea", ".gradle", ".mvn", ".imini", ".venv", "__pycache__");

    private final Sandbox sandbox;
    @Value("${agent.tool-timeout-seconds:60}") private int toolTimeoutSeconds;
    @Value("${nav.grep-max-file-kb:512}") private int grepMaxFileKb;

    public CodebaseTools(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public List<Tool> all() {
        return List.of(glob(), grep(), repoTree(), readMany(), gitStatus(), gitDiff());
    }

    // ---------------------------------------------------------------------
    // Tool definitions
    // ---------------------------------------------------------------------

    public Tool glob() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", strProp("Glob to match, relative to the search dir, e.g. \"**/*.java\" or \"src/**/Test*.kt\"."));
        props.put("dir", strProp("Optional sub-directory to search under (default: workspace root)."));
        props.put("max_results", intProp("Optional cap on matches (default 200)."));
        return new Tool("glob",
                "Find files whose path matches a glob pattern. Fast, deterministic; use this to locate "
                        + "files by name before reading them.",
                schema(props, "pattern"), false, args -> {
            try {
                Path root = sandbox.root();
                Path base = resolveDir(args);
                String denied = sandbox.enforcePath("glob", base.toString(), false);
                if (denied != null) return denied;
                int cap = Math.max(1, intArg(args, "max_results", 200));
                List<String> hits = globFiles(root, base, str(args, "pattern"), cap);
                return hits.isEmpty() ? "(no matches)" : String.join("\n", hits);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool grep() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("pattern", strProp("Regular expression to search for in file contents."));
        props.put("dir", strProp("Optional sub-directory to search under (default: workspace root)."));
        props.put("glob", strProp("Optional file filter, e.g. \"**/*.java\" (default: all text files)."));
        props.put("ignore_case", boolProp("Case-insensitive match (default false)."));
        props.put("max_results", intProp("Optional cap on matching lines (default 100)."));
        return new Tool("grep",
                "Search file contents by regex and return matching lines as 'path:line: text'. Use this "
                        + "to find where something is defined or used.",
                schema(props, "pattern"), false, args -> {
            try {
                Path root = sandbox.root();
                Path base = resolveDir(args);
                String denied = sandbox.enforcePath("grep", base.toString(), false);
                if (denied != null) return denied;
                int flags = boolArg(args, "ignore_case", false) ? Pattern.CASE_INSENSITIVE : 0;
                Pattern re = Pattern.compile(str(args, "pattern"), flags);
                String globFilter = str(args, "glob");
                int cap = Math.max(1, intArg(args, "max_results", 100));
                return grepText(root, base, re, globFilter.isBlank() ? null : globFilter, cap, grepMaxFileKb);
            } catch (PatternSyntaxException pe) {
                return "ERROR: invalid regex: " + pe.getMessage();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool repoTree() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("dir", strProp("Optional sub-directory (default: workspace root)."));
        props.put("max_depth", intProp("How deep to descend (default 3)."));
        props.put("max_entries", intProp("Cap on entries shown (default 300)."));
        return new Tool("repo_tree",
                "Show an indented directory tree of the project (heavy dirs like .git/target/node_modules "
                        + "are skipped). Use this first to get oriented in an unfamiliar repo.",
                schema(props), false, args -> {
            try {
                Path root = sandbox.root();
                Path base = resolveDir(args);
                String denied = sandbox.enforcePath("repo_tree", base.toString(), false);
                if (denied != null) return denied;
                int depth = Math.max(1, intArg(args, "max_depth", 3));
                int max = Math.max(1, intArg(args, "max_entries", 300));
                return tree(root, base, depth, max);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool readMany() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("paths", strArrayProp("Files to read (relative to the workspace root)."));
        return new Tool("read_many",
                "Read several text files in one call, each under a '==> path <==' header. Handy for "
                        + "comparing or reviewing related files together.",
                schema(props, "paths"), false, args -> {
            try {
                Object raw = args.get("paths");
                if (!(raw instanceof List<?> list) || list.isEmpty()) {
                    return "ERROR: provide a non-empty 'paths' array.";
                }
                Path root = sandbox.root();
                StringBuilder sb = new StringBuilder();
                int files = 0;
                for (Object o : list) {
                    if (files >= 20) { sb.append("\n...[stopped at 20 files]"); break; }
                    String rel = String.valueOf(o);
                    Path p = root.resolve(rel).normalize();
                    String denied = sandbox.enforcePath("read_many", p.toString(), false);
                    sb.append("==> ").append(rel).append(" <==\n");
                    if (denied != null) { sb.append(denied).append("\n\n"); files++; continue; }
                    try {
                        sb.append(truncate(Files.readString(p), 4000)).append("\n\n");
                    } catch (Exception e) {
                        sb.append("ERROR: ").append(e.getMessage()).append("\n\n");
                    }
                    files++;
                    if (sb.length() > 16000) { sb.append("...[output truncated]"); break; }
                }
                return sb.toString().strip();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool gitStatus() {
        return new Tool("git_status",
                "Show the git status of the workspace (branch + changed/untracked files), porcelain format.",
                schema(new LinkedHashMap<>()), false, args -> {
            String out = runGit(List.of("status", "--porcelain=v1", "-b"));
            return out.isBlank() ? "(clean working tree)" : truncate(out, 6000);
        });
    }

    public Tool gitDiff() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("staged", boolProp("Diff the staged/index changes instead of the working tree (default false)."));
        props.put("path", strProp("Optional path to limit the diff to."));
        return new Tool("git_diff",
                "Show a unified git diff of the workspace. Set staged=true for index changes, or pass a "
                        + "path to limit it. Use it to review what changed before committing.",
                schema(props), false, args -> {
            List<String> cmd = new ArrayList<>(List.of("diff"));
            if (boolArg(args, "staged", false)) cmd.add("--cached");
            String path = str(args, "path");
            if (!path.isBlank()) { cmd.add("--"); cmd.add(path); }
            String out = runGit(cmd);
            return out.isBlank() ? "(no changes)" : truncate(out, 8000);
        });
    }

    // ---------------------------------------------------------------------
    // Pure, unit-testable core (no Spring; takes an explicit root)
    // ---------------------------------------------------------------------

    public static List<String> globFiles(Path root, Path base, String pattern, int cap) throws IOException {
        PathMatcher pm = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> out = new ArrayList<>();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (!dir.equals(base) && IGNORE_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (pm.matches(base.relativize(file))) out.add(rel(root, file));
                return out.size() >= cap ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(String::compareTo);
        return out;
    }

    public static String grepText(Path root, Path base, Pattern re, String globFilter,
                                  int maxMatches, int maxFileKb) throws IOException {
        PathMatcher filter = globFilter == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + globFilter);
        long maxBytes = (long) maxFileKb * 1024L;
        List<String> out = new ArrayList<>();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (!dir.equals(base) && IGNORE_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (a.size() > maxBytes) return FileVisitResult.CONTINUE;
                if (filter != null && !filter.matches(base.relativize(file))) return FileVisitResult.CONTINUE;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE; // binary / non-UTF-8 -> skip
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (re.matcher(lines.get(i)).find()) {
                        out.add(rel(root, file) + ":" + (i + 1) + ": " + truncate(lines.get(i).strip(), 200));
                        if (out.size() >= maxMatches) return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (out.isEmpty()) return "(no matches)";
        String body = String.join("\n", out);
        return out.size() >= maxMatches ? body + "\n...[stopped at " + maxMatches + " matches]" : body;
    }

    public static String tree(Path root, Path base, int maxDepth, int maxEntries) throws IOException {
        StringBuilder sb = new StringBuilder(rel(root, base).isEmpty() ? "." : rel(root, base)).append("/\n");
        int[] count = {0};
        treeInto(sb, base, "", 1, maxDepth, maxEntries, count);
        if (count[0] >= maxEntries) sb.append("...[stopped at ").append(maxEntries).append(" entries]\n");
        return sb.toString().strip();
    }

    private static void treeInto(StringBuilder sb, Path dir, String prefix, int depth,
                                 int maxDepth, int maxEntries, int[] count) throws IOException {
        if (depth > maxDepth) return;
        // sort: directories first, then files, each alphabetical
        TreeMap<String, Path> dirs = new TreeMap<>();
        TreeMap<String, Path> files = new TreeMap<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (!IGNORE_DIRS.contains(n)) dirs.put(n, p);
                } else {
                    files.put(n, p);
                }
            });
        }
        List<Map.Entry<String, Path>> entries = new ArrayList<>();
        entries.addAll(dirs.entrySet());
        entries.addAll(files.entrySet());
        for (Map.Entry<String, Path> e : entries) {
            if (count[0] >= maxEntries) return;
            boolean isDir = Files.isDirectory(e.getValue());
            sb.append(prefix).append(isDir ? e.getKey() + "/" : e.getKey()).append("\n");
            count[0]++;
            if (isDir) treeInto(sb, e.getValue(), prefix + "  ", depth + 1, maxDepth, maxEntries, count);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Path resolveDir(Map<String, Object> args) {
        String dir = str(args, "dir");
        Path root = sandbox.root();
        return dir.isBlank() ? root : root.resolve(dir).normalize();
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
                Thread t = new Thread(r, "git-reader");
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
            ex.shutdown();
            return out;
        } catch (Exception e) {
            if (ex != null) ex.shutdownNow();
            return "ERROR: " + e.getMessage() + " (is git installed and is the workspace a git repo?)";
        }
    }

    static String rel(Path root, Path p) {
        try {
            return root.relativize(p).toString().replace('\\', '/');
        } catch (Exception e) {
            return p.toString().replace('\\', '/');
        }
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.of(required));
        return s;
    }

    private static Map<String, Object> strProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
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

    private static int intArg(Map<String, Object> args, String key, int dflt) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? dflt : Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static boolean boolArg(Map<String, Object> args, String key, boolean dflt) {
        Object v = args.get(key);
        if (v instanceof Boolean b) return b;
        return v == null ? dflt : Boolean.parseBoolean(String.valueOf(v).trim().toLowerCase(Locale.ROOT));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }
}
