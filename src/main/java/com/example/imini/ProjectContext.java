package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Project memory, like Claude Code's CLAUDE.md -- now layered. Several memory files are loaded (in a
 * fixed order) and concatenated into the system prompt, so the agent knows your conventions, commands,
 * and preferences. Files may pull in others with {@code @path} imports (depth/size/cycle-guarded).
 *
 * <p>Load order (see {@link MemoryLoader#CANDIDATES}): {@code .claude/CLAUDE.md}, {@code CLAUDE.md},
 * {@code IMINI.md}, {@code AGENTS.md}, then {@code .claude/rules/*.md} (sorted), then
 * {@code CLAUDE.local.md} last (so local overrides win). Read fresh each call, so edits take effect
 * without a restart (captured at session start for {@code /chat}).
 *
 * <p>{@link #diagnostics()} / {@link #report()} back the {@code /memory} command and
 * {@code GET /memory/files}: they show exactly which files loaded, in what order, their size, and why
 * (direct, imported, or skipped).
 */
@Component
public class ProjectContext {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectContext.class);

    @Value("${memory.import-max-depth:3}") private int maxDepth;
    @Value("${memory.max-file-kb:64}") private int maxFileKb;

    private final Path root = Path.of("").toAbsolutePath().normalize();

    private record Loaded(String text, List<MemoryLoader.Source> sources) {}

    /** The concatenated memory text appended to the system prompt (or "" when no memory files exist). */
    public String addendum() {
        return load().text();
    }

    /** Diagnostics: every memory file that loaded (or was skipped), in order, with size + reason. */
    public List<MemoryLoader.Source> diagnostics() {
        return load().sources();
    }

    /** True for the built-in {@code /memory} command. */
    public boolean isMemoryCommand(String msg) {
        return msg != null && msg.trim().equals("/memory");
    }

    /** Human-readable memory diagnostics for the {@code /memory} command. */
    public String report() {
        List<MemoryLoader.Source> sources = diagnostics();
        if (sources.isEmpty()) {
            return "No project memory files found. Looked for: " + String.join(", ", MemoryLoader.CANDIDATES)
                    + " and .claude/rules/*.md (relative to the workspace root).";
        }
        int totalBytes = sources.stream().filter(s -> !s.reason().startsWith("skipped"))
                .mapToInt(MemoryLoader.Source::bytes).sum();
        StringBuilder sb = new StringBuilder("Loaded project memory (" + sources.size()
                + " entr" + (sources.size() == 1 ? "y" : "ies") + ", " + totalBytes + " bytes):\n");
        for (MemoryLoader.Source s : sources) {
            sb.append("  ").append("  ".repeat(Math.max(0, s.depth()))).append(s.depth() > 0 ? "- import " : "- ")
              .append(s.path()).append("  [").append(s.reason()).append("]");
            if (s.bytes() > 0) sb.append(" ").append(s.bytes()).append("B");
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private Loaded load() {
        List<MemoryLoader.Source> diag = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        StringBuilder text = new StringBuilder();

        for (String name : candidateFiles()) {
            Path p = Path.of(name);
            if (!Files.isRegularFile(p)) continue;
            String key = relKey(p);
            if (!visited.add(key)) continue; // de-dupe if a rule also matched a candidate
            String content = readCapped(p);
            if (content == null) {
                diag.add(new MemoryLoader.Source(name, "skipped: exceeds " + maxFileKb + "KB cap", 0, 0));
                continue;
            }
            diag.add(new MemoryLoader.Source(name, reasonFor(name), content.length(), 0));
            String expanded = MemoryLoader.expand(key, content, this::resolve, maxDepth, diag, visited, 0);
            if (!expanded.isBlank()) {
                text.append("\n\n--- Memory (").append(name).append(") ---\n").append(expanded.strip());
            }
        }
        return new Loaded(text.toString(), diag);
    }

    /** Candidate files in load order: the fixed list, with .claude/rules/*.md (sorted) before the local file. */
    private List<String> candidateFiles() {
        List<String> files = new ArrayList<>();
        for (String name : MemoryLoader.CANDIDATES) {
            if (name.equals("CLAUDE.local.md")) {
                files.addAll(rules()); // rules load after project files, before the local override
            }
            files.add(name);
        }
        return files;
    }

    private List<String> rules() {
        Path dir = Path.of(".claude/rules");
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".md")).map(Path::toString).sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[memory] could not list .claude/rules: " + e.getMessage());
            return List.of();
        }
    }

    /** Resolve an @path import relative to the importer, confined to the workspace, size-capped. */
    private MemoryLoader.Resolved resolve(String importerKey, String importPath) {
        try {
            Path baseDir = Path.of(importerKey).getParent();
            Path rel = baseDir == null ? Path.of(importPath) : baseDir.resolve(importPath);
            Path abs = root.resolve(rel).normalize();
            if (!abs.startsWith(root) || !Files.isRegularFile(abs)) return null; // outside workspace / missing
            String content = readCapped(abs);
            if (content == null) return null;
            return new MemoryLoader.Resolved(root.relativize(abs).toString(), content);
        } catch (Exception e) {
            return null;
        }
    }

    private String readCapped(Path p) {
        try {
            if (Files.size(p) > (long) maxFileKb * 1024) return null;
            return Files.readString(p);
        } catch (Exception e) {
            log.warn("[memory] could not read " + p + ": " + e.getMessage());
            return null;
        }
    }

    private String relKey(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString();
    }

    private static String reasonFor(String name) {
        if (name.endsWith("CLAUDE.local.md")) return "loaded (local override)";
        if (name.startsWith(".claude/rules")) return "loaded (rule)";
        return "loaded (project memory)";
    }
}
