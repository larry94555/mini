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
    @Value("${skills.registry:}") private String registryPath;

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
        for (String spec : repoList()) {
            String url = SkillLibrary.splitRepoSpec(spec)[0];
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
    private void pull(String spec) throws Exception {
        String[] ur = SkillLibrary.splitRepoSpec(spec);
        String url = ur[0], ref = ur[1];
        Path repo = cacheDir().resolve(SkillLibrary.repoSlug(url));
        Files.createDirectories(cacheDir());
        if (Files.isDirectory(repo.resolve(".git"))) {
            if (!ref.isEmpty()) {
                gitRun(List.of("git", "-C", repo.toString(), "fetch", "--depth", "1", "origin", ref));
                gitRun(List.of("git", "-C", repo.toString(), "checkout", "-q", "FETCH_HEAD"));
            } else {
                gitRun(List.of("git", "-C", repo.toString(), "pull", "--ff-only"));
            }
        } else {
            List<String> cmd = new ArrayList<>(List.of("git", "clone", "--depth", "1"));
            if (!ref.isEmpty()) { cmd.add("--branch"); cmd.add(ref); } // pin to a branch or tag
            cmd.add(url); cmd.add(repo.toString());
            gitRun(cmd);
        }
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

    // ---- registry (provenance: search + verified install) -----------------

    private Path registryFile() {
        return (registryPath == null || registryPath.isBlank()) ? null : sandbox.root().resolve(registryPath);
    }

    private synchronized List<SkillManifest.Entry> manifest() {
        Path f = registryFile();
        if (f == null || !Files.isRegularFile(f)) return List.of();
        try {
            return SkillManifest.parse(Files.readString(f));
        } catch (Exception e) {
            log.warn("[skills] could not read registry " + f + ": " + e.getMessage());
            return List.of();
        }
    }

    public Tool searchSkillsTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "What kind of skill to look for."));
        props.put("k", Map.of("type", "integer", "description", "How many results (optional)."));
        return new Tool("search_skills",
                "Search the skill REGISTRY (a manifest of available skills with provenance) for skills "
                        + "matching a query. Returns name, description, source, version, and whether it is "
                        + "already installed. Use install_skill to add one.",
                schema(props, List.of("query")), false, args -> {
            if (!enabled) return "skills are disabled";
            List<SkillManifest.Entry> entries = manifest();
            if (entries.isEmpty()) return "no skill registry configured (set skills.registry) or it is empty";
            int k = args.get("k") instanceof Number n ? n.intValue() : 5;
            StringBuilder sb = new StringBuilder("registry matches:\n");
            for (SkillManifest.Entry e : SkillManifest.search(entries, str(args.get("query")), k)) {
                sb.append("- ").append(e.name());
                if (!e.version().isEmpty()) sb.append(" (").append(e.version()).append(")");
                sb.append(byName(e.name()) != null ? " [installed]" : "")
                  .append(": ").append(e.description().isEmpty() ? "(no description)" : e.description())
                  .append("\n");
            }
            return sb.toString().strip();
        });
    }

    public Tool installSkillTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "Registry skill name to install."));
        return new Tool("install_skill",
                "Install a skill from the registry: fetch its instructions from the registry source, "
                        + "VERIFY the content hash, and save it locally so load_skill can use it. "
                        + "Read-only: no skill code is executed.",
                schema(props, List.of("name")), true, args -> {
            if (!enabled) return "skills are disabled";
            return install(str(args.get("name")));
        });
    }

    synchronized String install(String name) {
        Path f = registryFile();
        if (f == null) return "no skill registry configured (set skills.registry)";
        SkillManifest.Entry entry = null;
        for (SkillManifest.Entry e : manifest()) {
            if (e.name().equalsIgnoreCase(name == null ? "" : name.trim())) { entry = e; break; }
        }
        if (entry == null) return "skill not found in registry: " + name;
        if (entry.source().isEmpty()) return "registry entry has no source: " + name;
        try {
            Path src = f.getParent().resolve(entry.source()).normalize();
            if (!src.startsWith(f.getParent())) return "ERROR: registry source escapes the registry directory";
            if (Files.isDirectory(src)) src = src.resolve("SKILL.md");
            if (!Files.isRegularFile(src)) return "ERROR: registry source not found: " + entry.source();
            String body = Files.readString(src);
            if (!SkillManifest.matches(entry, body)) {
                return "ERROR: hash mismatch for '" + entry.name() + "' (expected " + entry.sha256()
                        + ", got " + SkillManifest.sha256(body) + ") -- not installed";
            }
            String warn = (entry.sha256() == null || entry.sha256().isBlank()) ? " [warning: unpinned, no sha256]" : "";
            String saved = save(entry.name(), entry.description(), body, entry.source(), entry.version(),
                    SkillManifest.sha256(body));
            return saved + warn;
        } catch (Exception e) {
            return "ERROR: could not install '" + name + "': " + e.getMessage();
        }
    }

    public Tool refreshSkillsTool() {
        return new Tool("refresh_skills",
                "Re-fetch the configured (allowlisted) remote skill repositories and reload all skills. "
                        + "Read-only: it clones/pulls instruction files only and runs no skill code.",
                schema(new LinkedHashMap<>(), List.of()), false, args -> refresh());
    }

    synchronized String save(String rawName, String description, String body) {
        return save(rawName, description, body, null, null, null);
    }

    /** Save a skill, optionally recording provenance (source/version/sha256) in the front-matter. */
    synchronized String save(String rawName, String description, String body,
                             String source, String version, String sha256) {
        String name = sanitize(rawName);
        if (name.isEmpty()) return "ERROR: invalid skill name";
        if (body == null || body.isBlank()) return "ERROR: empty skill body";
        try {
            Path skillDir = dir().resolve(name);
            Files.createDirectories(skillDir);
            StringBuilder fm = new StringBuilder("---\nname: ").append(name)
                    .append("\ndescription: ").append(description == null ? "" : description.strip());
            if (source != null && !source.isBlank()) fm.append("\nsource: ").append(source.strip());
            if (version != null && !version.isBlank()) fm.append("\nversion: ").append(version.strip());
            if (sha256 != null && !sha256.isBlank()) fm.append("\nsha256: ").append(sha256.strip());
            // strip any front-matter already present in the body so we do not double-wrap it
            String pure = body.strip();
            if (pure.startsWith("---")) {
                int second = pure.indexOf("\n---", 3);
                if (second > 0) pure = pure.substring(second + 4).strip();
            }
            String content = fm.append("\n---\n").append(pure).append("\n").toString();
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
