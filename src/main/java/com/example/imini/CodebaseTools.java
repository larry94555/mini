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
import java.util.regex.Matcher;
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
        return List.of(glob(), grep(), repoTree(), readMany(), outline(), findSymbol(), findReferences(),
                gitStatus(), gitDiff(), gitLog(), gitBlame());
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

    public Tool gitLog() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Optional path to limit the history to (a file or directory)."));
        props.put("max_count", intProp("How many recent commits to show (default 20)."));
        return new Tool("git_log",
                "Show recent commit history (hash, date, author, subject), most recent first. Pass a "
                        + "path to see just that file's history. Use it to learn when/why something changed.",
                schema(props), false, args -> {
            String path = str(args, "path");
            if (!path.isBlank()) {
                String denied = sandbox.enforcePath("git_log", path, false);
                if (denied != null) return denied;
            }
            String out = runGit(gitLogArgs(path, intArg(args, "max_count", 20)));
            return out.isBlank() ? "(no history)" : truncate(out, 6000);
        });
    }

    public Tool gitBlame() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to blame (who last changed each line)."));
        props.put("start", intProp("Optional first line of a range (1-based)."));
        props.put("end", intProp("Optional last line of the range (defaults to start+200 if only start given)."));
        return new Tool("git_blame",
                "Show, for each line of a file, the commit/author/date that last changed it. Pass a "
                        + "start (and optional end) to blame just a range -- prefer a range on large files.",
                schema(props, "path"), false, args -> {
            String path = str(args, "path");
            if (path.isBlank()) return "ERROR: provide a 'path'.";
            String denied = sandbox.enforcePath("git_blame", path, false);
            if (denied != null) return denied;
            String out = runGit(gitBlameArgs(path, intArg(args, "start", 0), intArg(args, "end", 0)));
            return out.isBlank() ? "(no blame output)" : truncate(out, 8000);
        });
    }

    /** git argv for the log tool (static + pure, so it is unit-testable). */
    public static List<String> gitLogArgs(String path, int maxCount) {
        List<String> a = new ArrayList<>(List.of(
                "log", "--pretty=format:%h %ad %an: %s", "--date=short", "-n",
                String.valueOf(Math.max(1, maxCount))));
        if (path != null && !path.isBlank()) { a.add("--"); a.add(path); }
        return a;
    }

    /** git argv for the blame tool (static + pure, so it is unit-testable). */
    public static List<String> gitBlameArgs(String path, int start, int end) {
        List<String> a = new ArrayList<>(List.of("blame", "--date=short"));
        if (start > 0 && end >= start) {
            a.add("-L"); a.add(start + "," + end);
        } else if (start > 0) {
            a.add("-L"); a.add(start + ",+200");   // start to a bounded window when no end given
        }
        a.add("--"); a.add(path);
        return a;
    }

    public Tool outline() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to outline (its declarations: classes, methods, functions)."));
        return new Tool("outline",
                "List the declarations (classes/interfaces/methods/functions) in a single file with "
                        + "their line numbers. Use this to understand a file's structure before reading "
                        + "it all. Supports java, python, js/ts, kotlin, go.",
                schema(props, "path"), false, args -> {
            try {
                Path root = sandbox.root();
                String path = str(args, "path");
                String denied = sandbox.enforcePath("outline", path, false);
                if (denied != null) return denied;
                Path p = root.resolve(path).normalize();
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                List<Symbol> syms = extractSymbols(p.getFileName().toString(), lines);
                if (syms.isEmpty()) {
                    return "(no symbols recognized in " + rel(root, p)
                            + "; supported: java, python, js/ts, kotlin, go)";
                }
                StringBuilder sb = new StringBuilder(rel(root, p)).append("\n");
                for (Symbol s : syms) {
                    sb.append(String.format("%6d  %-9s %s%n", s.line(), s.kind(), s.name()));
                }
                return sb.toString().strip();
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool findSymbol() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", strProp("Exact symbol name to find the DECLARATION of (not every mention)."));
        props.put("dir", strProp("Optional sub-directory to search under (default: workspace root)."));
        props.put("glob", strProp("Optional file filter, e.g. \"**/*.java\" (default: all supported files)."));
        props.put("max_results", intProp("Optional cap on matches (default 50)."));
        return new Tool("find_symbol",
                "Find where a symbol (class/method/function/type) is DEFINED across the repo, returning "
                        + "'path:line: kind name'. Unlike grep, this matches declarations, not usages.",
                schema(props, "name"), false, args -> {
            try {
                Path root = sandbox.root();
                Path base = resolveDir(args);
                String denied = sandbox.enforcePath("find_symbol", base.toString(), false);
                if (denied != null) return denied;
                String name = str(args, "name");
                if (name.isBlank()) return "ERROR: provide a symbol 'name'.";
                String globFilter = str(args, "glob");
                int cap = Math.max(1, intArg(args, "max_results", 50));
                return findSymbol(root, base, name, globFilter.isBlank() ? null : globFilter, cap, grepMaxFileKb);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
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
    // Symbol awareness (declaration-level; regex heuristics, not a real parser)
    // ---------------------------------------------------------------------

    /** A declaration found in a file. */
    public record Symbol(int line, String kind, String name, String text) {}

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed)\\s+)*"
                    + "(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\\s+)+"
                    + "[\\w$.<>\\[\\],?&\\s]*?(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws [^{;]+)?[{;]");

    private static final Pattern PY_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)");

    private static final Pattern JS_CLASS = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern JS_FUNC = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s+(\\w+)");
    private static final Pattern JS_ARROW = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?(?:\\([^)]*\\)|[\\w$]+)\\s*=>");
    private static final Pattern TS_TYPE = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:declare\\s+)?(interface|enum|type)\\s+(\\w+)");

    private static final Pattern KT_TYPE = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|internal|open|sealed|data|abstract|final|enum)\\s+)*"
                    + "(class|interface|object)\\s+(\\w+)");
    private static final Pattern KT_FUN = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|internal|open|override|suspend|inline|abstract|final)\\s+)*"
                    + "fun\\s+(?:<[^>]+>\\s*)?(\\w+)");

    private static final Pattern GO_FUNC = Pattern.compile(
            "^\\s*func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)\\s*\\(");
    private static final Pattern GO_TYPE = Pattern.compile(
            "^\\s*type\\s+(\\w+)\\s+(?:struct|interface)\\b");

    /** Declarations in a file, by extension. Empty for unsupported types. */
    public static List<Symbol> extractSymbols(String fileName, List<String> lines) {
        return switch (extension(fileName)) {
            case "java" -> javaSymbols(lines);
            case "py" -> pythonSymbols(lines);
            case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> jsSymbols(lines);
            case "kt", "kts" -> kotlinSymbols(lines);
            case "go" -> goSymbols(lines);
            default -> List.of();
        };
    }

    private static List<Symbol> javaSymbols(List<String> lines) {
        List<Symbol> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i), t = line.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            Matcher m = JAVA_TYPE.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, m.group(1), m.group(2), t)); continue; }
            m = JAVA_METHOD.matcher(line);
            if (m.find()) out.add(new Symbol(i + 1, "method", m.group(1), t));
        }
        return out;
    }

    private static List<Symbol> pythonSymbols(List<String> lines) {
        List<Symbol> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i), t = line.strip();
            if (t.startsWith("#")) continue;
            Matcher m = PY_CLASS.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, "class", m.group(1), t)); continue; }
            m = PY_DEF.matcher(line);
            if (m.find()) out.add(new Symbol(i + 1, "def", m.group(1), t));
        }
        return out;
    }

    private static List<Symbol> jsSymbols(List<String> lines) {
        List<Symbol> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i), t = line.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            Matcher m = JS_CLASS.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, "class", m.group(1), t)); continue; }
            m = TS_TYPE.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, m.group(1), m.group(2), t)); continue; }
            m = JS_FUNC.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, "function", m.group(1), t)); continue; }
            m = JS_ARROW.matcher(line);
            if (m.find()) out.add(new Symbol(i + 1, "function", m.group(1), t));
        }
        return out;
    }

    private static List<Symbol> kotlinSymbols(List<String> lines) {
        List<Symbol> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i), t = line.strip();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            Matcher m = KT_TYPE.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, m.group(1), m.group(2), t)); continue; }
            m = KT_FUN.matcher(line);
            if (m.find()) out.add(new Symbol(i + 1, "fun", m.group(1), t));
        }
        return out;
    }

    private static List<Symbol> goSymbols(List<String> lines) {
        List<Symbol> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i), t = line.strip();
            if (t.startsWith("//")) continue;
            Matcher m = GO_TYPE.matcher(line);
            if (m.find()) { out.add(new Symbol(i + 1, "type", m.group(1), t)); continue; }
            m = GO_FUNC.matcher(line);
            if (m.find()) out.add(new Symbol(i + 1, "func", m.group(1), t));
        }
        return out;
    }

    /** Walk the tree and report declarations whose name equals {@code name} ('path:line: kind name'). */
    public Tool findReferences() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", strProp("Exact identifier to find USAGES of across the repo (whole-word)."));
        props.put("dir", strProp("Optional sub-directory to search under (default: workspace root)."));
        props.put("glob", strProp("Optional file filter, e.g. \"**/*.java\" (default: all files)."));
        props.put("max_results", intProp("Optional cap on matches (default 50)."));
        return new Tool("find_references",
                "Find every USAGE of an identifier across the repo (whole-identifier matches, so 'user' "
                        + "won't match 'username'), returning 'path:line: text' with declaration sites marked "
                        + "[def]. Complements find_symbol (which finds only the declaration). Heuristic, not a "
                        + "typed resolver: it can over-match a name reused elsewhere.",
                schema(props, "name"), false, args -> {
            try {
                Path root = sandbox.root();
                Path base = resolveDir(args);
                String denied = sandbox.enforcePath("find_references", base.toString(), false);
                if (denied != null) return denied;
                String name = str(args, "name");
                if (name.isBlank()) return "ERROR: provide an identifier 'name'.";
                String globFilter = str(args, "glob");
                int cap = Math.max(1, intArg(args, "max_results", 50));
                return findReferences(root, base, name.trim(),
                        globFilter.isBlank() ? null : globFilter, cap, grepMaxFileKb);
            } catch (IOException e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public static String findReferences(Path root, Path base, String name, String globFilter,
                                        int maxMatches, int maxFileKb) throws IOException {
        PathMatcher fileFilter = globFilter == null ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + globFilter);
        long maxBytes = (long) maxFileKb * 1024L;
        List<SymbolRefs.Ref> refs = new ArrayList<>();
        boolean[] truncated = {false};
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (!dir.equals(base) && IGNORE_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (a.size() > maxBytes) return FileVisitResult.CONTINUE;
                if (fileFilter != null && !fileFilter.matches(base.relativize(file))) return FileVisitResult.CONTINUE;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }
                // declaration lines for this name in this file (to mark [def])
                java.util.Set<Integer> defLines = new java.util.HashSet<>();
                for (Symbol sym : extractSymbols(file.getFileName().toString(), lines)) {
                    if (name.equals(sym.name())) defLines.add(sym.line());
                }
                for (int i = 0; i < lines.size(); i++) {
                    if (SymbolRefs.references(lines.get(i), name)) {
                        refs.add(new SymbolRefs.Ref(rel(root, file), i + 1, defLines.contains(i + 1),
                                truncate(lines.get(i).strip(), 200)));
                        if (refs.size() >= maxMatches) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return SymbolRefs.render(refs, name, maxMatches, truncated[0]);
    }

    public static String findSymbol(Path root, Path base, String name, String globFilter,
                                    int maxMatches, int maxFileKb) throws IOException {
        PathMatcher fileFilter = globFilter == null ? null
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
                if (fileFilter != null && !fileFilter.matches(base.relativize(file))) return FileVisitResult.CONTINUE;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return FileVisitResult.CONTINUE;
                }
                for (Symbol s : extractSymbols(file.getFileName().toString(), lines)) {
                    if (name.equals(s.name())) {
                        out.add(rel(root, file) + ":" + s.line() + ": " + s.kind() + " " + s.name());
                        if (out.size() >= maxMatches) return FileVisitResult.TERMINATE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (out.isEmpty()) return "(no declaration of '" + name + "' found)";
        String body = String.join("\n", out);
        return out.size() >= maxMatches ? body + "\n...[stopped at " + maxMatches + " matches]" : body;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
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
