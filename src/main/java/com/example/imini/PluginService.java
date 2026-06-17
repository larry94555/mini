package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Export/install plugin packs: portable bundles of skills, subagents, and slash commands. Export reads
 * the workspace's {@code skills/}, {@code agents/}, and {@code commands/} files into a JSON manifest;
 * install writes a manifest's entries back into those folders, validating every path so an install can
 * never escape the workspace ({@link PluginPack} owns the rules). All file writes are confined to the
 * workspace root.
 */
@Component
public class PluginService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginService.class);

    @Value("${skills.dir:skills}") private String skillsDir;
    @Value("${agents.dir:agents}") private String agentsDir;
    @Value("${commands.dir:commands}") private String commandsDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path root = Path.of("").toAbsolutePath().normalize();

    /** Build a pack from everything currently in the workspace. */
    public PluginPack.Pack exportPack(String name, String version, String description) {
        List<PluginPack.Entry> entries = new ArrayList<>();
        // skills: folder form (skills/<name>/SKILL.md) or flat (skills/<name>.md)
        Path sd = root.resolve(skillsDir).normalize();
        if (Files.isDirectory(sd)) {
            try (Stream<Path> s = Files.list(sd)) {
                s.sorted().forEach(p -> {
                    try {
                        if (Files.isDirectory(p)) {
                            Path skill = p.resolve("SKILL.md");
                            if (Files.isRegularFile(skill)) entries.add(new PluginPack.Entry("skill",
                                    p.getFileName().toString(), Files.readString(skill)));
                        } else if (p.toString().endsWith(".md")) {
                            entries.add(new PluginPack.Entry("skill",
                                    stem(p), Files.readString(p)));
                        }
                    } catch (Exception e) { log.warn("[plugin] skill read " + p + ": " + e.getMessage()); }
                });
            } catch (Exception ignore) {}
        }
        addFlat(entries, agentsDir, "agent");
        addFlat(entries, commandsDir, "command");
        return new PluginPack.Pack(PluginPack.FORMAT, name == null || name.isBlank() ? "workspace-pack" : name,
                version == null || version.isBlank() ? "1" : version,
                description == null ? "" : description, entries);
    }

    public String exportJson(String name, String version, String description) throws Exception {
        return mapper.writeValueAsString(exportPack(name, version, description));
    }

    /** Install a pack JSON; returns a report (installed, skipped, errors). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> install(String packJson, boolean overwrite) {
        List<String> installed = new ArrayList<>(), skipped = new ArrayList<>(), errors = new ArrayList<>();
        PluginPack.Pack pack;
        try {
            Map<String, Object> raw = mapper.readValue(packJson, Map.class);
            List<PluginPack.Entry> entries = new ArrayList<>();
            Object es = raw.get("entries");
            if (es instanceof List<?> list) {
                for (Object o : list) if (o instanceof Map<?, ?> m) {
                    entries.add(new PluginPack.Entry(str(m, "type"), str(m, "name"), str(m, "content")));
                }
            }
            pack = new PluginPack.Pack(str(raw, "format"), str(raw, "name"), str(raw, "version"),
                    str(raw, "description"), entries);
        } catch (Exception e) {
            return Map.of("error", "could not parse pack: " + e.getMessage());
        }

        for (PluginPack.Entry e : pack.entries()) {
            String rel = PluginPack.targetPath(e);
            if (rel == null) { skipped.add((e.name() == null ? "?" : e.name()) + " (invalid type/name)"); continue; }
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) { errors.add(rel + " (escapes workspace)"); continue; }
            try {
                if (Files.exists(target) && !overwrite) { skipped.add(rel + " (exists)"); continue; }
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, e.content() == null ? "" : e.content());
                installed.add(rel);
            } catch (Exception ex) {
                errors.add(rel + " (" + ex.getMessage() + ")");
            }
        }
        log.info("[plugin] install: " + installed.size() + " installed, " + skipped.size()
                + " skipped, " + errors.size() + " errors");
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pack", pack.name());
        out.put("installed", installed);
        out.put("skipped", skipped);
        out.put("errors", errors);
        out.put("summary", PluginPack.summarize(pack.entries()));
        return out;
    }

    /** Counts of what's currently available, for the UI. */
    public Map<String, Object> summary() {
        return Map.of(
                "skills", countDir(skillsDir, true),
                "agents", countDir(agentsDir, false),
                "commands", countDir(commandsDir, false));
    }

    private void addFlat(List<PluginPack.Entry> entries, String dir, String type) {
        Path d = root.resolve(dir).normalize();
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.toString().endsWith(".md")).sorted().forEach(p -> {
                try { entries.add(new PluginPack.Entry(type, stem(p), Files.readString(p))); }
                catch (Exception e) { log.warn("[plugin] " + type + " read " + p + ": " + e.getMessage()); }
            });
        } catch (Exception ignore) {}
    }

    private int countDir(String dir, boolean skills) {
        Path d = root.resolve(dir).normalize();
        if (!Files.isDirectory(d)) return 0;
        try (Stream<Path> s = Files.list(d)) {
            return (int) s.filter(p -> Files.isDirectory(p) || p.toString().endsWith(".md")).count();
        } catch (Exception e) { return 0; }
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".md") ? n.substring(0, n.length() - 3) : n;
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
