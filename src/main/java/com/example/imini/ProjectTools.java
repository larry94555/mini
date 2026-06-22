package com.example.imini;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Track B PR #3 — {@code create_project}: write an entire project (a manifest of relative path + content
 * entries) under a destination root, transactionally.
 *
 * <p><strong>Safety:</strong> every target path must resolve inside a granted {@code READ_WRITE} root
 * (checked with {@link WorkspaceRoots#canWrite}); a path that escapes (via {@code ..} or an absolute target)
 * is rejected. The tool is {@code mutating}, so it goes through the normal approval flow; the approval
 * payload is summarized (root, file count, total bytes, tree) by {@link PermissionService} rather than
 * dumping every file's content.
 *
 * <p><strong>Plan-first:</strong> with {@code plan_only=true} the tool writes nothing and returns the tree +
 * per-file byte counts, so the manifest can be reviewed before committing. (In the harness's {@code plan}
 * permission mode the engine records the call without executing it; {@code plan_only} is the explicit,
 * mode-independent way to preview.)
 *
 * <p><strong>Transactional:</strong> all files are first staged into a temp directory; only if every file
 * stages successfully are they moved into place. A move failure rolls back the moves already done. Existing
 * target files are refused unless {@code overwrite=true}. With multi-root disabled the destination must be
 * inside the default {@code READ_WRITE} root, exactly as any other write.
 */
@Component
public class ProjectTools {

  private final WorkspaceRoots workspaceRoots;
  private final AuditLog audit;

  public ProjectTools(WorkspaceRoots workspaceRoots, AuditLog audit) {
    this.workspaceRoots = workspaceRoots;
    this.audit = audit;
  }

  public List<Tool> all() {
    return List.of(createProjectTool());
  }

  /** One file in the manifest: a workspace-relative path and its UTF-8 content. */
  public record Entry(String path, String content) {
    public int bytes() {
      return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
  }

  public Tool createProjectTool() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("root", strProp("Absolute destination directory. Must be inside a granted read_write root."));
    Map<String, Object> filesProp = new LinkedHashMap<>();
    filesProp.put("type", "array");
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("type", "object");
    Map<String, Object> itemProps = new LinkedHashMap<>();
    itemProps.put("path", strProp("Path relative to root (no leading slash, no '..')."));
    itemProps.put("content", strProp("UTF-8 file content."));
    item.put("properties", itemProps);
    filesProp.put("items", item);
    filesProp.put("description", "The manifest: the files to create, each {path, content}.");
    props.put("files", filesProp);
    props.put("plan_only", boolProp("If true, write nothing and return the planned tree + byte counts."));
    props.put("overwrite", boolProp("If true, allow overwriting existing files in the destination."));

    return new Tool(
        "create_project",
        "Create a whole project at once: write a manifest of files (each {path, content}) under an absolute "
            + "destination 'root'. The root must be inside a granted read_write workspace root. Mutating and "
            + "approval-gated; the approval shows a summary (root, file count, total bytes, tree). Pass "
            + "plan_only=true to preview the tree without writing. Writes are transactional (all-or-nothing) "
            + "and refuse to overwrite existing files unless overwrite=true.",
        schema(props, "root", "files"),
        true,
        this::execute);
  }

  // --- executor ---------------------------------------------------------

  @SuppressWarnings("unchecked")
  String execute(Map<String, Object> args) {
    String rootStr = str(args.get("root"));
    if (rootStr == null || rootStr.isBlank()) {
      return "create_project requires an absolute 'root'.";
    }
    Path root;
    try {
      root = Path.of(rootStr);
    } catch (Exception e) {
      return "create_project: invalid root '" + rootStr + "'.";
    }
    if (!root.isAbsolute()) {
      return "create_project requires an ABSOLUTE 'root'; got relative '" + rootStr + "'.";
    }
    root = root.normalize();

    List<Entry> entries;
    try {
      entries = parseManifest(args.get("files"));
    } catch (IllegalArgumentException e) {
      return "create_project: " + e.getMessage();
    }
    if (entries.isEmpty()) {
      return "create_project: 'files' manifest is empty.";
    }

    // Resolve + confine every target. Reject path escapes and writes outside a granted read_write root.
    List<Path> targets = new ArrayList<>();
    for (Entry e : entries) {
      Path abs;
      try {
        abs = root.resolve(e.path()).normalize();
      } catch (Exception ex) {
        return "create_project: invalid path '" + e.path() + "'.";
      }
      if (!abs.startsWith(root)) {
        return "create_project: '" + e.path() + "' escapes the destination root.";
      }
      if (!canWrite(abs)) {
        return "DENIED: '" + abs + "' is outside any granted read_write workspace root.";
      }
      targets.add(abs);
    }

    int totalBytes = 0;
    for (Entry e : entries) {
      totalBytes += e.bytes();
    }
    String tree = renderTree(entries);
    String summary =
        "root: " + root + "\nfiles: " + entries.size() + "; total bytes: " + totalBytes + "\n" + tree;

    boolean planOnly = bool(args.get("plan_only"));
    if (planOnly) {
      return "[plan] create_project (nothing written):\n" + summary;
    }

    boolean overwrite = bool(args.get("overwrite"));
    if (!overwrite) {
      for (Path t : targets) {
        if (Files.exists(t)) {
          return "create_project: '" + root.relativize(t) + "' already exists; pass overwrite=true to replace.";
        }
      }
    }

    try {
      writeTransactionally(root, entries, targets);
    } catch (IOException e) {
      audit.record("agent", "create_project", root + " (" + entries.size() + " files)", "failed: " + e.getMessage());
      return "create_project: write failed and was rolled back: " + e.getMessage();
    }

    audit.record("agent", "create_project", root + " (" + entries.size() + " files, " + totalBytes + " bytes)", "created");
    return "Created " + entries.size() + " file(s) under " + root + " (" + totalBytes + " bytes).\n" + tree;
  }

  /**
   * Stage every file into a temp directory first; only if all stage successfully, move them into place.
   * A failure during the move phase rolls back the moves already performed. Staging-first means content/IO
   * errors fail before the destination is touched at all.
   */
  static void writeTransactionally(Path root, List<Entry> entries, List<Path> targets) throws IOException {
    Path staging = Files.createTempDirectory("imini-create-");
    List<Path> staged = new ArrayList<>();
    try {
      for (int i = 0; i < entries.size(); i++) {
        Path s = staging.resolve("f" + i);
        Files.writeString(s, entries.get(i).content() == null ? "" : entries.get(i).content());
        staged.add(s);
      }
      // Move phase: track what we put in place so we can undo on failure.
      List<Path> placed = new ArrayList<>();
      List<Boolean> preexisting = new ArrayList<>();
      try {
        for (int i = 0; i < targets.size(); i++) {
          Path t = targets.get(i);
          boolean existed = Files.exists(t);
          if (t.getParent() != null) {
            Files.createDirectories(t.getParent());
          }
          Files.move(staged.get(i), t, StandardCopyOption.REPLACE_EXISTING);
          placed.add(t);
          preexisting.add(existed);
        }
      } catch (IOException moveErr) {
        // Roll back: delete files we created (leave any that pre-existed alone — best effort).
        for (int j = placed.size() - 1; j >= 0; j--) {
          if (!preexisting.get(j)) {
            try {
              Files.deleteIfExists(placed.get(j));
            } catch (IOException ignore) {
              // best effort
            }
          }
        }
        throw moveErr;
      }
    } finally {
      // Clean up the staging dir (and any unmoved staged files).
      try {
        for (Path s : staged) {
          Files.deleteIfExists(s);
        }
        Files.deleteIfExists(staging);
      } catch (IOException ignore) {
        // best effort
      }
    }
  }

  // --- pure helpers (unit-tested) --------------------------------------

  /** Parse the {@code files} arg into entries. Throws {@link IllegalArgumentException} with a clear note. */
  @SuppressWarnings("unchecked")
  static List<Entry> parseManifest(Object files) {
    if (!(files instanceof List<?> list)) {
      throw new IllegalArgumentException("'files' must be a list of {path, content} objects.");
    }
    List<Entry> out = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m)) {
        throw new IllegalArgumentException("each manifest entry must be an object with 'path' and 'content'.");
      }
      Object p = m.get("path");
      Object c = m.get("content");
      String path = p == null ? null : String.valueOf(p);
      if (path == null || path.isBlank()) {
        throw new IllegalArgumentException("a manifest entry is missing 'path'.");
      }
      if (path.startsWith("/") || path.startsWith("\\")) {
        throw new IllegalArgumentException("path '" + path + "' must be relative (no leading slash).");
      }
      out.add(new Entry(path, c == null ? "" : String.valueOf(c)));
    }
    return out;
  }

  /** A sorted, indented tree of the manifest with per-file byte counts. */
  static String renderTree(List<Entry> entries) {
    TreeMap<String, Integer> sorted = new TreeMap<>();
    for (Entry e : entries) {
      sorted.put(e.path().replace('\\', '/'), e.bytes());
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> e : sorted.entrySet()) {
      sb.append("  ").append(e.getKey()).append("  (").append(e.getValue()).append(" bytes)\n");
    }
    return sb.toString();
  }

  /** Build the approval-payload summary used by PermissionService (kept here so the format lives with the tool). */
  @SuppressWarnings("unchecked")
  static Map<String, Object> summarize(Map<String, Object> args) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("root", str(args.get("root")));
    try {
      List<Entry> entries = parseManifest(args.get("files"));
      int total = 0;
      List<String> paths = new ArrayList<>();
      for (Entry e : entries) {
        total += e.bytes();
        paths.add(e.path() + " (" + e.bytes() + " b)");
      }
      out.put("fileCount", entries.size());
      out.put("totalBytes", total);
      out.put("tree", paths);
    } catch (RuntimeException e) {
      out.put("error", e.getMessage());
    }
    return out;
  }

  private boolean canWrite(Path abs) {
    if (workspaceRoots != null) {
      return workspaceRoots.canWrite(abs.toString());
    }
    return false; // no registry -> nothing is writable via create_project
  }

  private static String str(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static boolean bool(Object o) {
    return o instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(o));
  }

  private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("type", "object");
    s.put("properties", properties);
    if (required.length > 0) {
      s.put("required", List.of(required));
    }
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
}
