package com.example.imini;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the individual Tool instances.
 *
 *   all()        -> tools the MAIN agent uses: read_file, view, list_dir, write_file, edit_file,
 *                   run_command, web_fetch. (web_search is delegated to the sub-agent.)
 *   webFetch()   -> jsoup page fetch + main-article extraction
 *   webSearch()  -> DuckDuckGo HTML search (given only to the sub-agent)
 *
 * write_file and edit_file snapshot the target through CheckpointStore first, so edits can be rewound.
 */
@Component
public class BuiltinTools {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BuiltinTools.class);


    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)   // CNN etc. redirect; without this the body is empty
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final CheckpointStore checkpoints;
    private final TodoStore todos;
    private final Sandbox sandbox;
    private final RetrievalService retrieval;
    private final PreviewStore previews;
    @Value("${agent.tool-timeout-seconds:60}")
    private int toolTimeoutSeconds;

    public BuiltinTools(CheckpointStore checkpoints, TodoStore todos, Sandbox sandbox,
                        RetrievalService retrieval, PreviewStore previews) {
        this.checkpoints = checkpoints;
        this.todos = todos;
        this.sandbox = sandbox;
        this.retrieval = retrieval;
        this.previews = previews;
    }

    /** Tools available to the main agent. */
    public List<Tool> all() {
        return List.of(readFile(), view(), listDir(), writeFile(), editFile(), applyPatch(),
                previewPatch(), applyPreviewedPatch(), discardPreviewedPatch(),
                runCommand(), webFetch(), todoWrite());
    }

    // ---------------------------------------------------------------------
    // File tools
    // ---------------------------------------------------------------------

    public Tool readFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Path to the text file to read."));
        return new Tool("read_file", "Read a UTF-8 text file from disk.",
                schema(props, "path"), false, args -> {
            try {
                String path = str(args, "path");
                String denied = sandbox.enforcePath("read_file", path, false);
                if (denied != null) return denied;
                return truncate(Files.readString(Path.of(path)), 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool view() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to view."));
        props.put("start_line", intProp("Optional 1-based first line to show."));
        props.put("end_line", intProp("Optional 1-based last line to show (inclusive)."));
        return new Tool("view",
                "Read a text file with line numbers (optionally a line range). Use this before edit_file "
                        + "so you can copy an exact, unique snippet to replace.",
                schema(props, "path"), false, args -> {
            try {
                String path = str(args, "path");
                String denied = sandbox.enforcePath("view", path, false);
                if (denied != null) return denied;
                List<String> lines = Files.readAllLines(Path.of(path));
                int start = Math.max(1, intArg(args, "start_line", 1));
                int end = Math.min(lines.size(), intArg(args, "end_line", lines.size()));
                StringBuilder sb = new StringBuilder();
                for (int i = start; i <= end; i++) {
                    sb.append(String.format("%6d\t%s%n", i, lines.get(i - 1)));
                }
                return sb.length() == 0 ? "(empty or range out of bounds)" : truncate(sb.toString(), 8000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool listDir() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Directory to list. Defaults to current directory."));
        return new Tool("list_dir", "List the entries in a directory.",
                schema(props), false, args -> {
            try {
                String p = args.containsKey("path") ? str(args, "path") : ".";
                String denied = sandbox.enforcePath("list_dir", p, false);
                if (denied != null) return denied;
                try (var stream = Files.list(Path.of(p))) {
                    String out = stream
                            .map(x -> (Files.isDirectory(x) ? "[dir]  " : "[file] ") + x.getFileName())
                            .sorted()
                            .collect(Collectors.joining("\n"));
                    return out.isBlank() ? "(empty)" : out;
                }
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool writeFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("Path to create or overwrite."));
        props.put("content", strProp("Full UTF-8 content to write."));
        return new Tool("write_file",
                "Create or overwrite a whole file. For changing part of an existing file, prefer edit_file. "
                        + "Mutating: requires approval.",
                schema(props, "path", "content"), true, args -> {
            try {
                String denied = sandbox.enforcePath("write_file", str(args, "path"), true);
                if (denied != null) return denied;
                Path p = Path.of(str(args, "path"));
                checkpoints.snapshot(p);                       // save before overwriting
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, str(args, "content"));
                retrieval.reindexFile(p);
                return "Wrote " + p.toAbsolutePath() + " (snapshot saved for rewind).";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool editFile() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("path", strProp("File to edit."));
        props.put("old_str", strProp("Exact text to find. Must appear EXACTLY ONCE in the file."));
        props.put("new_str", strProp("Text to replace it with."));
        return new Tool("edit_file",
                "Replace a unique, exact snippet in a file with new text. Fails if the snippet is missing "
                        + "or appears more than once. Snapshots the file first so it can be rewound. "
                        + "Mutating: requires approval.",
                schema(props, "path", "old_str", "new_str"), true, args -> {
            try {
                String denied = sandbox.enforcePath("edit_file", str(args, "path"), true);
                if (denied != null) return denied;
                Path p = Path.of(str(args, "path"));
                String content = Files.readString(p);
                String oldStr = str(args, "old_str");
                String newStr = str(args, "new_str");
                if (oldStr.isEmpty()) return "ERROR: old_str must not be empty.";
                int count = countOccurrences(content, oldStr);
                if (count == 0) return "ERROR: old_str was not found in " + p + ".";
                if (count > 1) {
                    return "ERROR: old_str appears " + count + " times in " + p
                            + "; include more surrounding text so it is unique.";
                }
                checkpoints.snapshot(p);
                Files.writeString(p, content.replace(oldStr, newStr));
                retrieval.reindexFile(p);
                return "Edited " + p.toAbsolutePath() + " (1 replacement; snapshot saved for rewind).";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Planning tool
    // ---------------------------------------------------------------------

    public Tool todoWrite() {
        // schema: { todos: [ { content: string, status: pending|in_progress|completed } ] }
        Map<String, Object> item = new LinkedHashMap<>();
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("content", strProp("What needs to be done."));
        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", List.of("pending", "in_progress", "completed"));
        statusProp.put("description", "pending | in_progress | completed");
        itemProps.put("status", statusProp);
        item.put("type", "object");
        item.put("properties", itemProps);
        item.put("required", List.of("content", "status"));

        Map<String, Object> todosProp = new LinkedHashMap<>();
        todosProp.put("type", "array");
        todosProp.put("items", item);
        todosProp.put("description", "The COMPLETE task list (always pass the whole list).");

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("todos", todosProp);

        return new Tool("todo_write",
                "Record or update your task checklist for a multi-step task. Always pass the FULL list. "
                        + "Use it to plan before you start and to mark steps in_progress/completed as you go.",
                schema(props, "todos"), false, args -> {
            try {
                Object raw = args.get("todos");
                List<TodoStore.Item> items = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            String content = String.valueOf(m.get("content"));
                            Object st = m.get("status");
                            items.add(new TodoStore.Item(content, st == null ? "pending" : String.valueOf(st)));
                        }
                    }
                }
                String sid = SessionContext.sessionId();
                todos.set(sid, items);
                String rendered = todos.render(sid);
                log.debug("[todo] updated:\n" + rendered);
                return "Updated todo list:\n" + rendered;
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Shell tool
    // ---------------------------------------------------------------------

    public Tool applyPatch() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("path", strProp("File path to modify or create."));
        itemProps.put("find", strProp("Exact, unique text to replace (omit when creating a new file)."));
        itemProps.put("replace", strProp("Replacement for 'find' (default empty = delete the snippet)."));
        itemProps.put("create", strProp("Full content for a NEW file (use instead of find/replace; errors if it exists)."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        Map<String, Object> editsProp = new LinkedHashMap<>();
        editsProp.put("type", "array");
        editsProp.put("items", item);
        editsProp.put("description",
                "Edits applied atomically: each is {path, find, replace} to modify, or {path, create} for a new file.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("edits", editsProp);

        return new Tool("apply_patch",
                "Apply SEVERAL file edits in one atomic step. Each edit either replaces a unique snippet "
                        + "(path + find + replace) or creates a new file (path + create). Everything is "
                        + "validated first and NOTHING is written if any edit is invalid; each changed file "
                        + "is snapshotted so it can be rewound. Review the result with git_diff. "
                        + "Mutating: requires approval.",
                schema(props, "edits"), true, args -> {
            try {
                Object raw = args.get("edits");
                if (!(raw instanceof List<?> list) || list.isEmpty()) {
                    return "ERROR: provide a non-empty 'edits' array.";
                }
                List<EditSpec> specs = new ArrayList<>();
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) return "ERROR: each edit must be an object.";
                    String path = sval(m, "path");
                    if (path == null || path.isBlank()) return "ERROR: each edit needs a 'path'.";
                    if (m.containsKey("create")) {
                        String c = sval(m, "create");
                        specs.add(new EditSpec(path, null, null, c == null ? "" : c));
                    } else {
                        specs.add(new EditSpec(path, sval(m, "find"),
                                sval(m, "replace") == null ? "" : sval(m, "replace"), null));
                    }
                }
                // confinement check for every target BEFORE touching disk (atomic)
                for (EditSpec e : specs) {
                    String denied = sandbox.enforcePath("apply_patch", e.path(), true);
                    if (denied != null) return denied;
                }
                // load existing files; creates simply won't be present
                Map<String, String> contents = new LinkedHashMap<>();
                for (EditSpec e : specs) {
                    if (contents.containsKey(e.path())) continue;
                    Path p = Path.of(e.path());
                    if (Files.exists(p)) contents.put(e.path(), Files.readString(p));
                }
                Map<String, String> result;
                try {
                    result = applyEdits(contents, specs);
                } catch (IllegalArgumentException bad) {
                    return "PATCH ABORTED (no changes written): " + bad.getMessage();
                }
                // write only the files whose content actually changed; snapshot each first
                List<String> changed = new ArrayList<>();
                checkpoints.beginBatch();   // group this patch's snapshots into one change set
                try {
                    for (Map.Entry<String, String> en : result.entrySet()) {
                        String original = contents.get(en.getKey());
                        if (en.getValue().equals(original)) continue;
                        Path p = Path.of(en.getKey());
                        checkpoints.snapshot(p);
                        if (p.getParent() != null) Files.createDirectories(p.getParent());
                        Files.writeString(p, en.getValue());
                        retrieval.reindexFile(p);
                        changed.add(en.getKey());
                    }
                } finally {
                    checkpoints.endBatch();
                }
                if (changed.isEmpty()) return "No changes (edits produced identical content).";
                return "Applied " + specs.size() + " edit(s) across " + changed.size() + " file(s): "
                        + String.join(", ", changed) + ". Snapshots saved as one change set; rewind undoes them together. Review with git_diff.";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    /** One planned edit: a find/replace on an existing file, or a create of a new file. */
    public record EditSpec(String path, String find, String replace, String create) {}

    /**
     * Pure, atomic application of edits to an in-memory view of file contents. Throws
     * IllegalArgumentException (writing nothing) if any edit is invalid: a create whose file already
     * exists, a find/replace on a missing file, or a 'find' that is absent or not unique. Edits apply
     * in order, so a create followed by a find/replace on the same new path works. Static + dependency
     * free so it is unit-testable.
     */
    // ---- patch preview / review (preview_patch, apply_previewed_patch, discard_previewed_patch) ----

    /** Parse the raw {edits} arg into EditSpecs; returns null + sets err[0] on a problem. */
    private List<EditSpec> parseEdits(Object raw, String[] err) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            err[0] = "ERROR: provide a non-empty 'edits' array.";
            return null;
        }
        List<EditSpec> specs = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) { err[0] = "ERROR: each edit must be an object."; return null; }
            String path = sval(m, "path");
            if (path == null || path.isBlank()) { err[0] = "ERROR: each edit needs a 'path'."; return null; }
            if (m.containsKey("create")) {
                String c = sval(m, "create");
                specs.add(new EditSpec(path, null, null, c == null ? "" : c));
            } else {
                specs.add(new EditSpec(path, sval(m, "find"),
                        sval(m, "replace") == null ? "" : sval(m, "replace"), null));
            }
        }
        return specs;
    }

    private static List<Map<String, String>> rawEdits(List<EditSpec> specs) {
        List<Map<String, String>> out = new ArrayList<>();
        for (EditSpec e : specs) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("path", e.path());
            if (e.create() != null) m.put("create", e.create());
            else { m.put("find", e.find() == null ? "" : e.find()); m.put("replace", e.replace() == null ? "" : e.replace()); }
            out.add(m);
        }
        return out;
    }

    private Tool previewPatch() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("path", strProp("File path to modify or create."));
        itemProps.put("find", strProp("Exact, unique text to replace (omit when creating)."));
        itemProps.put("replace", strProp("Replacement for 'find'."));
        itemProps.put("create", strProp("Full content for a NEW file."));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", itemProps);
        Map<String, Object> editsProp = new LinkedHashMap<>();
        editsProp.put("type", "array");
        editsProp.put("items", item);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("edits", editsProp);
        return new Tool("preview_patch",
                "Stage edits WITHOUT writing them and return a unified diff to review first. Same edit "
                        + "shape as apply_patch ({path,find,replace} or {path,create}). Follow up with "
                        + "apply_previewed_patch to write, or discard_previewed_patch to drop it. Not mutating.",
                schema(props, "edits"), false, args -> {
            try {
                String[] err = {null};
                List<EditSpec> specs = parseEdits(args.get("edits"), err);
                if (specs == null) return err[0];
                for (EditSpec e : specs) {
                    String denied = sandbox.enforcePath("preview_patch", e.path(), true);
                    if (denied != null) return denied;
                }
                Map<String, String> contents = new LinkedHashMap<>();
                for (EditSpec e : specs) {
                    if (contents.containsKey(e.path())) continue;
                    Path pa = Path.of(e.path());
                    if (Files.exists(pa)) contents.put(e.path(), Files.readString(pa));
                }
                Map<String, String> result;
                try {
                    result = applyEdits(contents, specs);
                } catch (IllegalArgumentException bad) {
                    return "PREVIEW FAILED (no changes staged): " + bad.getMessage();
                }
                List<PreviewStore.Hunk> hunks = buildHunks(specs);
                String diffText = hunkDiffText(hunks);
                String summary = hunkSummary(hunks);
                PreviewStore.Preview pv = previews.stage(SessionContext.sessionId(), summary, diffText, hunks);
                return "Staged preview " + pv.id() + " (" + summary + ", " + hunks.size() + " hunk(s))."
                        + " Nothing written yet.\n\n" + diffText
                        + "\nApply with apply_previewed_patch (optionally hunks=\"0,2\"), or discard_previewed_patch.";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private Tool applyPreviewedPatch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", strProp("Preview id to apply (default: the most recent staged preview)."));
        props.put("hunks", strProp("Which hunks to apply, e.g. \"0,2\" or \"1-3\" (default: all)."));
        return new Tool("apply_previewed_patch",
                "Apply a staged preview (from preview_patch), re-validating against the current files and "
                        + "snapshotting each change. Optionally apply only some hunks via 'hunks'; the rest "
                        + "stay staged. Mutating: requires approval.",
                schema(props), true, args -> applyPreview(SessionContext.sessionId(), sval(args, "id"), sval(args, "hunks")));
    }

    private Tool discardPreviewedPatch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", strProp("Preview id to discard (default: the most recent)."));
        props.put("hunks", strProp("Which hunks to discard, e.g. \"0,2\" (default: the whole preview)."));
        return new Tool("discard_previewed_patch",
                "Discard a staged preview, or just some of its hunks via 'hunks'. Not mutating.",
                schema(props), false, args ->
                        discardPreview(SessionContext.sessionId(), sval(args, "id"), sval(args, "hunks")));
    }

    /** Apply a staged preview: re-read current files, re-apply the edits, snapshot + write. Public for the UI. */
    /** Apply selected hunks of a staged preview; remaining hunks stay staged. Public for the UI. */
    public String applyPreview(String sessionId, String id, String hunksSpec) {
        try {
            PreviewStore.Preview pv = previews.get(sessionId, id);
            if (pv == null) return "No staged preview" + (id == null || id.isBlank() ? "." : " with id " + id + ".");
            java.util.Set<Integer> sel = PreviewSelect.parse(hunksSpec, pv.hunks().size());
            if (sel.isEmpty()) return "No matching hunks to apply.";
            List<PreviewStore.Hunk> selected = PreviewSelect.pick(pv.hunks(), sel);

            List<EditSpec> specs = parseEdits(new ArrayList<Object>(hunkEdits(selected)), new String[]{null});
            if (specs == null) return "ERROR: could not read the staged hunks.";
            for (EditSpec e : specs) {
                String denied = sandbox.enforcePath("apply_previewed_patch", e.path(), true);
                if (denied != null) return denied;
            }
            Map<String, String> contents = new LinkedHashMap<>();
            for (EditSpec e : specs) {
                if (contents.containsKey(e.path())) continue;
                Path pa = Path.of(e.path());
                if (Files.exists(pa)) contents.put(e.path(), Files.readString(pa));
            }
            Map<String, String> result;
            try {
                result = applyEdits(contents, specs);
            } catch (IllegalArgumentException bad) {
                return "APPLY ABORTED (files changed since preview?): " + bad.getMessage();
            }
            List<String> changed = new ArrayList<>();
            checkpoints.beginBatch();
            try {
                for (Map.Entry<String, String> en : result.entrySet()) {
                    String original = contents.get(en.getKey());
                    if (en.getValue().equals(original)) continue;
                    Path pa = Path.of(en.getKey());
                    checkpoints.snapshot(pa);
                    if (pa.getParent() != null) Files.createDirectories(pa.getParent());
                    Files.writeString(pa, en.getValue());
                    retrieval.reindexFile(pa);
                    changed.add(en.getKey());
                }
            } finally {
                checkpoints.endBatch();
            }
            int remaining = restage(sessionId, pv, sel);
            String tail = remaining == 0 ? " Preview cleared." : " " + remaining + " hunk(s) remain staged.";
            if (changed.isEmpty()) return "No changes (selected hunks produced identical content)." + tail;
            return "Applied " + selected.size() + " hunk(s) from " + pv.id() + " across "
                    + changed.size() + " file(s): " + String.join(", ", changed)
                    + ". Snapshots saved as one change set; review with git_diff." + tail;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /** Discard a whole preview, or just selected hunks (leaving the rest staged). */
    public String discardPreview(String sessionId, String id, String hunksSpec) {
        if (hunksSpec == null || hunksSpec.isBlank()) {
            return previews.discard(sessionId, id) ? "Discarded the staged preview." : "No staged preview to discard.";
        }
        PreviewStore.Preview pv = previews.get(sessionId, id);
        if (pv == null) return "No staged preview to discard.";
        java.util.Set<Integer> sel = PreviewSelect.parse(hunksSpec, pv.hunks().size());
        int remaining = restage(sessionId, pv, sel);
        return "Discarded " + sel.size() + " hunk(s)." + (remaining == 0 ? " Preview cleared."
                : " " + remaining + " hunk(s) remain staged.");
    }

    /** Re-stage a preview keeping only the hunks NOT in {@code applied} (re-indexed); returns remaining count. */
    private int restage(String sessionId, PreviewStore.Preview pv, java.util.Set<Integer> applied) {
        List<PreviewStore.Hunk> remaining = new ArrayList<>();
        int i = 0;
        for (PreviewStore.Hunk h : pv.hunks()) {
            if (!applied.contains(h.index())) {
                remaining.add(new PreviewStore.Hunk(i++, h.path(), h.kind(), h.added(), h.removed(), h.diff(), h.edit()));
            }
        }
        previews.replaceHunks(sessionId, pv.id(), hunkSummary(remaining), hunkDiffText(remaining), remaining);
        return remaining.size();
    }

    // ---- hunk helpers ----

    private List<PreviewStore.Hunk> buildHunks(List<EditSpec> specs) throws Exception {
        List<PreviewStore.Hunk> hunks = new ArrayList<>();
        int idx = 0;
        for (EditSpec e : specs) {
            Path pa = Path.of(e.path());
            String orig = Files.exists(pa) ? Files.readString(pa) : null;
            DiffRender.FileDiff fd;
            try {
                Map<String, String> one = new LinkedHashMap<>();
                if (orig != null) one.put(e.path(), orig);
                Map<String, String> res = applyEdits(one, List.of(e));
                fd = DiffRender.unified(e.path(), orig, res.get(e.path()));
            } catch (IllegalArgumentException bad) {
                fd = new DiffRender.FileDiff(e.path(), "modify", 0, 0,
                        "(" + e.path() + ": depends on earlier hunks — " + bad.getMessage() + ")");
            }
            hunks.add(new PreviewStore.Hunk(idx++, e.path(), fd.kind(), fd.added(), fd.removed(), fd.diff(), rawEdit(e)));
        }
        return hunks;
    }

    private static Map<String, String> rawEdit(EditSpec e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("path", e.path());
        if (e.create() != null) m.put("create", e.create());
        else {
            m.put("find", e.find() == null ? "" : e.find());
            m.put("replace", e.replace() == null ? "" : e.replace());
        }
        return m;
    }

    private static List<Map<String, String>> hunkEdits(List<PreviewStore.Hunk> hunks) {
        List<Map<String, String>> out = new ArrayList<>();
        for (PreviewStore.Hunk h : hunks) out.add(h.edit());
        return out;
    }

    private static String hunkDiffText(List<PreviewStore.Hunk> hunks) {
        StringBuilder sb = new StringBuilder();
        for (PreviewStore.Hunk h : hunks) sb.append("[").append(h.index()).append("] ").append(h.diff());
        return sb.toString();
    }

    private static String hunkSummary(List<PreviewStore.Hunk> hunks) {
        int add = 0, rem = 0;
        java.util.Set<String> files = new java.util.LinkedHashSet<>();
        for (PreviewStore.Hunk h : hunks) {
            if ("unchanged".equals(h.kind())) continue;
            files.add(h.path());
            add += h.added();
            rem += h.removed();
        }
        return files.size() + " file(s), +" + add + " -" + rem;
    }


    public static Map<String, String> applyEdits(Map<String, String> contents, List<EditSpec> edits) {
        Map<String, String> work = new LinkedHashMap<>(contents);
        for (int i = 0; i < edits.size(); i++) {
            EditSpec e = edits.get(i);
            String tag = "edit[" + i + "] " + e.path() + ": ";
            if (e.create() != null) {
                if (work.containsKey(e.path())) {
                    throw new IllegalArgumentException(tag + "file already exists (use find/replace to modify it)");
                }
                work.put(e.path(), e.create());
            } else {
                String c = work.get(e.path());
                if (c == null) throw new IllegalArgumentException(tag + "file not found");
                if (e.find() == null || e.find().isEmpty()) {
                    throw new IllegalArgumentException(tag + "'find' must not be empty");
                }
                int first = c.indexOf(e.find());
                if (first < 0) throw new IllegalArgumentException(tag + "'find' text not present");
                if (c.indexOf(e.find(), first + 1) >= 0) {
                    throw new IllegalArgumentException(tag + "'find' is not unique; include more surrounding text");
                }
                String repl = e.replace() == null ? "" : e.replace();
                work.put(e.path(), c.substring(0, first) + repl + c.substring(first + e.find().length()));
            }
        }
        return work;
    }

    private static String sval(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public Tool runCommand() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("command", strProp("Shell command to run."));
        return new Tool("run_command",
                "Run a shell command and return combined stdout/stderr. Mutating: requires approval.",
                schema(props, "command"), true, args -> {
            try {
                String cmd = str(args, "command");
                String denied = sandbox.screenCommand(cmd);
                if (denied != null) return "DENIED: " + denied + ".";
                boolean win = System.getProperty("os.name").toLowerCase().contains("win");
                ProcessBuilder pb = new ProcessBuilder(sandbox.buildProcess(cmd, win));
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                // read on a separate thread so the timeout is real even if the process is chatty
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "run-command-reader");
                    t.setDaemon(true);
                    return t;
                });
                java.util.concurrent.Future<String> outF =
                        ex.submit(() -> new String(proc.getInputStream().readAllBytes()));
                boolean done = proc.waitFor(toolTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    proc.destroyForcibly();
                    ex.shutdownNow();
                    return "ERROR: command timed out after " + toolTimeoutSeconds + "s and was killed.";
                }
                String out = outF.get(5, java.util.concurrent.TimeUnit.SECONDS);
                ex.shutdown();
                return truncate(out.isBlank() ? "(no output)" : out, 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Web tools (jsoup)
    // ---------------------------------------------------------------------

    public Tool webFetch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("url", strProp("Absolute http(s) URL to fetch."));
        return new Tool("web_fetch",
                "Fetch a web page and return the main article region as clean text (jsoup).",
                schema(props, "url"), false, true /* untrusted output */, args -> {
            try {
                String url = str(args, "url");
                HttpResponse<String> resp = http.send(get(url), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    return "ERROR: HTTP " + resp.statusCode() + " when fetching " + url
                            + " (the site may be blocking automated requests).";
                }
                String text = HtmlExtractor.mainText(resp.body(), url);
                if (text.isBlank()) {
                    return "The page was fetched but contained no readable text. It may require "
                            + "JavaScript to render its content, so its headlines are not available this way.";
                }
                return truncate(text, 6000);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    public Tool webSearch() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", strProp("Search query."));
        return new Tool("web_search",
                "Search the web (DuckDuckGo) and return the top results with titles, URLs, and snippets.",
                schema(props, "query"), false, true /* untrusted output */, args -> {
            try {
                String q = str(args, "query");
                String url = "https://html.duckduckgo.com/html/?q="
                        + URLEncoder.encode(q, StandardCharsets.UTF_8);
                HttpResponse<String> resp = http.send(get(url), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    return "ERROR: HTTP " + resp.statusCode() + " from the search endpoint.";
                }

                Document doc = Jsoup.parse(resp.body(), url);
                List<String> out = new ArrayList<>();
                int rank = 1;
                for (Element result : doc.select("div.result")) {
                    Element link = result.selectFirst("a.result__a");
                    if (link == null) continue;
                    String title = link.text().trim();
                    String href = decodeDdg(link.attr("href"));
                    Element snip = result.selectFirst(".result__snippet");
                    String snippet = snip == null ? "" : snip.text().trim();
                    out.add(rank + ". " + title + "\n   " + href
                            + (snippet.isBlank() ? "" : "\n   " + snippet));
                    if (rank++ >= 6) break;
                }
                return out.isEmpty() ? "(no results)" : String.join("\n\n", out);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) mini-agent/0.3")
                .header("Accept", "text/html")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
    }

    private static String decodeDdg(String href) {
        if (href == null) return "";
        int i = href.indexOf("uddg=");
        if (i < 0) return href;
        String enc = href.substring(i + 5);
        int amp = enc.indexOf('&');
        if (amp >= 0) enc = enc.substring(0, amp);
        try {
            return URLDecoder.decode(enc, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return href;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, from = 0, idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            n++;
            from = idx + needle.length();
        }
        return n;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...[truncated " + (s.length() - max) + " chars]";
    }
}
