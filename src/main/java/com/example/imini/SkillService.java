package com.example.imini;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads "skills" (reusable instruction bundles) from a directory under the workspace, injects a short
 * index into the system prompt, and exposes them as tools: {@code load_skill} (progressive disclosure)
 * and {@code save_skill} (capture knowledge as a new skill). Discovery reuses the lexical scorer via
 * {@link SkillLibrary}. A skill is {@code <dir>/<name>/SKILL.md} or a flat {@code <dir>/<name>.md}.
 */
@Component
public class SkillService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SkillService.class);

    private final Sandbox sandbox;

    @Value("${skills.enabled:true}") private boolean enabled;
    @Value("${skills.dir:skills}") private String skillsDir;
    @Value("${skills.auto-load:false}") private boolean autoLoad;
    @Value("${skills.max-body:4000}") private int maxBody;

    private final List<SkillLibrary.Skill> skills = new ArrayList<>();

    public SkillService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    private Path dir() {
        return sandbox.root().resolve(skillsDir);
    }

    public synchronized void reload() {
        skills.clear();
        if (!enabled) return;
        Path base = dir();
        if (!Files.isDirectory(base)) return;
        try (Stream<Path> top = Files.list(base)) {
            List<Path> entries = top.sorted().toList();
            for (Path p : entries) {
                try {
                    if (Files.isDirectory(p)) {
                        Path md = p.resolve("SKILL.md");
                        if (Files.exists(md)) {
                            skills.add(SkillLibrary.parse(Files.readString(md), p.getFileName().toString()));
                        }
                    } else if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        String fn = p.getFileName().toString();
                        String stem = fn.substring(0, fn.length() - 3);
                        skills.add(SkillLibrary.parse(Files.readString(p), stem));
                    }
                } catch (Exception e) {
                    log.warn("[skills] could not read " + p + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[skills] could not list " + base + ": " + e.getMessage());
        }
        log.info("[skills] loaded " + skills.size() + " skill(s) from " + base);
    }

    public synchronized List<SkillLibrary.Skill> all() {
        return new ArrayList<>(skills);
    }

    /** Short index appended to the system prompt (or "" when disabled/empty). */
    public synchronized String indexAddendum() {
        if (!enabled || skills.isEmpty()) return "";
        return "\n\n--- Available skills (call load_skill with the name to load full instructions) ---\n"
                + SkillLibrary.index(skills);
    }

    /** When skills.auto-load is on, the best-matching skill's body for a query (or ""). */
    public synchronized String autoLoadAddendum(String query) {
        if (!enabled || !autoLoad || skills.isEmpty()) return "";
        List<SkillLibrary.Skill> top = SkillLibrary.select(skills, query, 1);
        if (top.isEmpty()) return "";
        return "\n\n" + SkillLibrary.format(top.get(0), maxBody);
    }

    private synchronized SkillLibrary.Skill byName(String name) {
        if (name == null) return null;
        for (SkillLibrary.Skill s : skills) {
            if (s.name().equalsIgnoreCase(name.trim())) return s;
        }
        return null;
    }

    // ---- tools --------------------------------------------------------------

    public Tool loadSkillTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "The skill name to load (see the skills list)."));
        return new Tool("load_skill",
                "Load the full instructions for a named skill (a reusable how-to). Call this when a task "
                        + "matches one of the available skills before doing the work.",
                schema(props, List.of("name")), false, args -> {
            if (!enabled) return "skills are disabled";
            SkillLibrary.Skill s = byName(args.get("name") == null ? "" : String.valueOf(args.get("name")));
            if (s == null) {
                return "skill not found. Available: " + (skills.isEmpty() ? "(none)" : SkillLibrary.index(skills));
            }
            return SkillLibrary.format(s, maxBody);
        });
    }

    public Tool saveSkillTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "Short skill name (letters, digits, dashes)."));
        props.put("description", Map.of("type", "string", "description", "One line on WHEN to use this skill."));
        props.put("body", Map.of("type", "string", "description", "The reusable instructions / how-to."));
        return new Tool("save_skill",
                "Save reusable knowledge as a new skill (written to the skills directory as a SKILL.md) "
                        + "so it can be loaded later with load_skill.",
                schema(props, List.of("name", "description", "body")), true, args -> {
            if (!enabled) return "skills are disabled";
            return save(str(args.get("name")), str(args.get("description")), str(args.get("body")));
        });
    }

    synchronized String save(String rawName, String description, String body) {
        String name = sanitize(rawName);
        if (name.isEmpty()) return "ERROR: invalid skill name";
        if (body == null || body.isBlank()) return "ERROR: empty skill body";
        try {
            Path skillDir = dir().resolve(name);
            Files.createDirectories(skillDir);
            String content = "---\nname: " + name + "\ndescription: "
                    + (description == null ? "" : description.strip()) + "\n---\n" + body.strip() + "\n";
            Files.writeString(skillDir.resolve("SKILL.md"), content);
            reload();
            return "saved skill '" + name + "' (" + skills.size() + " skill(s) now available)";
        } catch (Exception e) {
            return "ERROR: could not save skill: " + e.getMessage();
        }
    }

    /** Skill names may only contain letters, digits, dash, underscore (prevents path traversal). */
    static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[^A-Za-z0-9 _-]", "").replaceAll("\\s+", "-");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", required);
        return schema;
    }
}
