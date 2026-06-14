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
    @Value("${skills.repos:}") private String reposConfig;
    @Value("${skills.cache-dir:skill-cache}") private String cacheDirName;
    @Value("${skills.repo-timeout-seconds:60}") private int repoTimeoutSeconds;
    @Value("${skills.repos-on-start:true}") private boolean reposOnStart;

    private final List<SkillLibrary.Skill> skills = new ArrayList<>();

    public SkillService(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    @PostConstruct
    public void init() {
        if (reposOnStart && !repoList().isEmpty()) {
            refresh(); // clone/pull configured repos, then reload
        } else {
            reload();
        }
    }

    private Path dir() {
        return sandbox.root().resolve(skillsDir);
    }

    private Path cacheDir() {
        return sandbox.root().resolve(cacheDirName);
    }

    /** Allowlisted remote skill repos (the config IS the allowlist; nothing else is ever cloned). */
    private List<String> repoList() {
        List<String> out = new ArrayList<>();
        if (reposConfig == null) return out;
        for (String u : reposConfig.split(",")) {
            String t = u.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public synchronized void reload() {
        skills.clear();
        if (!enabled) return;
        List<List<SkillLibrary.Skill>> sources = new ArrayList<>();
        sources.add(scanDir(dir())); // local skills take precedence over remote
        for (String url : repoList()) {
            Path repo = cacheDir().resolve(SkillLibrary.repoSlug(url));
            if (!Files.isDirectory(repo)) continue;
            Path skillsSub = repo.resolve("skills");
            sources.add(scanDir(Files.isDirectory(skillsSub) ? skillsSub : repo));
        }
        skills.addAll(SkillLibrary.merge(sources)); // local-overrides-remote, earlier-repo-wins
        log.info("[skills] loaded " + skills.size() + " skill(s) (local + " + repoList().size() + " repo(s))");
    }

    /** Scan one directory for skills: {@code <name>/SKILL.md} folders or flat {@code <name>.md} files. */
    private List<SkillLibrary.Skill> scanDir(Path base) {
        List<SkillLibrary.Skill> out = new ArrayList<>();
        if (base == null || !Files.isDirectory(base)) return out;
        try (Stream<Path> top = Files.list(base)) {
            for (Path p : top.sorted().toList()) {
                try {
                    if (Files.isDirectory(p)) {
                        Path md = p.resolve("SKILL.md");
                        if (Files.exists(md)) {
                            out.add(SkillLibrary.parse(Files.readString(md), p.getFileName().toString()));
                        }
                    } else if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        String fn = p.getFileName().toString();
                        out.add(SkillLibrary.parse(Files.readString(p), fn.substring(0, fn.length() - 3)));
                    }
                } catch (Exception e) {
                    log.warn("[skills] could not read " + p + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[skills] could not list " + base + ": " + e.getMessage());
        }
        return out;
    }

    /** Clone/pull every configured (allowlisted) remote skill repo into the cache, then reload. */
    public synchronized String refresh() {
        if (!enabled) return "skills are disabled";
        List<String> repos = repoList();
        if (repos.isEmpty()) {
            reload();
            return "no remote skill repos configured; " + skills.size() + " local skill(s)";
        }
        int ok = 0;
        for (String url : repos) {
            try {
                pull(url);
                ok++;
            } catch (Exception e) {
                log.warn("[skills] could not fetch " + url + ": " + e.getMessage());
            }
        }
        reload();
        return "refreshed " + ok + "/" + repos.size() + " repo(s); " + skills.size() + " skill(s) available";
    }

    /** Read-only clone (or fast-forward pull) of an allowlisted repo into the cache. No code is run. */
    private void pull(String url) throws Exception {
        Path repo = cacheDir().resolve(SkillLibrary.repoSlug(url));
        Files.createDirectories(cacheDir());
        List<String> cmd = new ArrayList<>();
        if (Files.isDirectory(repo.resolve(".git"))) {
            cmd.add("git"); cmd.add("-C"); cmd.add(repo.toString());
            cmd.add("pull"); cmd.add("--ff-only");
        } else {
            cmd.add("git"); cmd.add("clone"); cmd.add("--depth"); cmd.add("1");
            cmd.add(url); cmd.add(repo.toString());
        }
        gitRun(cmd);
    }

    private void gitRun(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(sandbox.root().toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes(); // drain so the process can finish
        boolean done = proc.waitFor(repoTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            throw new RuntimeException("git timed out after " + repoTimeoutSeconds + "s");
        }
        if (proc.exitValue() != 0) throw new RuntimeException("git exit " + proc.exitValue());
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

    public Tool refreshSkillsTool() {
        return new Tool("refresh_skills",
                "Re-fetch the configured (allowlisted) remote skill repositories and reload all skills. "
                        + "Read-only: it clones/pulls instruction files only and runs no skill code.",
                schema(new LinkedHashMap<>(), List.of()), false, args -> refresh());
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
