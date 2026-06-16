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
    private final Database db;

    @Value("${skills.enabled:true}") private boolean enabled;
    @Value("${skills.dir:skills}") private String skillsDir;
    @Value("${skills.auto-load:false}") private boolean autoLoad;
    @Value("${skills.max-body:4000}") private int maxBody;
    @Value("${skills.repos:}") private String reposConfig;
    @Value("${skills.cache-dir:skill-cache}") private String cacheDirName;
    @Value("${skills.repo-timeout-seconds:60}") private int repoTimeoutSeconds;
    @Value("${skills.repos-on-start:true}") private boolean reposOnStart;
    @Value("${skills.registry:}") private String registryPath;
    @Value("${skills.disabled:}") private String disabledConfig;

    private final List<SkillLibrary.Skill> skills = new ArrayList<>();
    private final java.util.Set<String> disabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // sessionId -> (skill-name-lowercased -> enabled) ; per-session override of the global default
    private final Map<String, Map<String, Boolean>> sessionOverrides = new java.util.concurrent.ConcurrentHashMap<>();

    public SkillService(Sandbox sandbox, Database db) {
        this.sandbox = sandbox;
        this.db = db;
    }

    @PostConstruct
    public void init() {
        if (disabledConfig != null) {
            for (String d : disabledConfig.split(",")) {
                String t = d.trim();
                if (!t.isEmpty()) disabled.add(t.toLowerCase(Locale.ROOT));
            }
        }
        loadDisabledState(); // persisted toggles survive restart
        loadSessionOverrides();
        if (reposOnStart && !repoList().isEmpty()) {
            refresh(); // clone/pull configured repos, then reload
        } else {
            reload();
        }
    }

    private boolean isDisabled(String name) {
        return name != null && disabled.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Resolve effective enablement: a per-session override wins over the global default. Pure. */
    static boolean effectiveEnabled(boolean global, Boolean override) {
        return override != null ? override : global;
    }

    private Boolean sessionOverride(String sessionId, String name) {
        if (sessionId == null) return null;
        Map<String, Boolean> m = sessionOverrides.get(sessionId);
        return m == null ? null : m.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Is a skill effectively enabled for this session (override, else global)? */
    private boolean isEnabledFor(String sessionId, String name) {
        return effectiveEnabled(!isDisabled(name), sessionOverride(sessionId, name));
    }

    private synchronized List<SkillLibrary.Skill> enabledSkills() {
        return enabledSkillsFor(null);
    }

    private synchronized List<SkillLibrary.Skill> enabledSkillsFor(String sessionId) {
        List<SkillLibrary.Skill> out = new ArrayList<>();
        for (SkillLibrary.Skill sk : skills) if (isEnabledFor(sessionId, sk.name())) out.add(sk);
        return out;
    }

    /** A session's per-session overrides as [{name, enabled}] (for export bundles). */
    public synchronized List<Map<String, Object>> sessionOverridesFor(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Boolean> m = sessionId == null ? null : sessionOverrides.get(sessionId);
        if (m != null) {
            for (Map.Entry<String, Boolean> e : m.entrySet()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", e.getKey());
                r.put("enabled", e.getValue());
                out.add(r);
            }
        }
        return out;
    }

    /** Set a per-session override (true/false) for a skill. */
    public synchronized boolean setSessionEnabled(String sessionId, String name, boolean on) {
        if (byName(name) == null || sessionId == null || sessionId.isBlank()) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        sessionOverrides.computeIfAbsent(sessionId, k -> new java.util.concurrent.ConcurrentHashMap<>()).put(key, on);
        persistSessionState(sessionId, key, Boolean.valueOf(on));
        return true;
    }

    /** Remove a per-session override (revert to the global default). */
    public synchronized boolean clearSessionEnabled(String sessionId, String name) {
        if (sessionId == null) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        Map<String, Boolean> m = sessionOverrides.get(sessionId);
        if (m != null) m.remove(key);
        persistSessionState(sessionId, key, null);
        return true;
    }

    /** Skills for the management UI: name, description, and enabled flag. */
    public synchronized List<Map<String, Object>> listForUi() {
        return listForUi(null);
    }

    public synchronized List<Map<String, Object>> listForUi(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillLibrary.Skill sk : skills) {
            boolean global = !isDisabled(sk.name());
            Boolean override = sessionOverride(sessionId, sk.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", sk.name());
            m.put("description", sk.description());
            m.put("enabled", effectiveEnabled(global, override)); // effective for this session
            m.put("global", global);
            m.put("override", override); // null = no per-session override
            m.put("argumentHint", sk.argumentHint());
            m.put("allowedTools", sk.allowedTools());
            out.add(m);
        }
        return out;
    }

    /** Enable/disable a skill by name; returns true if the skill exists. */
    public synchronized boolean setEnabled(String name, boolean on) {
        if (byName(name) == null) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (on) disabled.remove(key); else disabled.add(key);
        persistState(key, on);
        return true;
    }

    private void loadDisabledState() {
        if (db == null || !db.available()) return;
        try {
            db.query("SELECT name, enabled FROM skill_state", rs -> {
                try {
                    if (rs.getInt(2) == 0) disabled.add(rs.getString(1).toLowerCase(Locale.ROOT));
                } catch (Exception ignore) {
                }
                return new int[0];
            });
        } catch (Exception e) {
            log.warn("[skills] could not load skill_state: " + e.getMessage());
        }
    }

    private void persistState(String key, boolean on) {
        if (db == null || !db.available()) return; // in-memory only when no DB
        try {
            db.update("INSERT INTO skill_state(name, enabled) VALUES(?, ?) "
                    + "ON CONFLICT(name) DO UPDATE SET enabled=excluded.enabled", key, on ? 1 : 0);
        } catch (Exception e) {
            log.warn("[skills] could not persist skill_state: " + e.getMessage());
        }
    }

    private void loadSessionOverrides() {
        if (db == null || !db.available()) return;
        try {
            db.query("SELECT session_id, name, enabled FROM session_skill_state", rs -> {
                try {
                    sessionOverrides.computeIfAbsent(rs.getString(1),
                            k -> new java.util.concurrent.ConcurrentHashMap<>())
                            .put(rs.getString(2).toLowerCase(Locale.ROOT), rs.getInt(3) != 0);
                } catch (Exception ignore) {
                }
                return new int[0];
            });
        } catch (Exception e) {
            log.warn("[skills] could not load session_skill_state: " + e.getMessage());
        }
    }

    private void persistSessionState(String sessionId, String key, Boolean on) {
        if (db == null || !db.available()) return;
        try {
            if (on == null) {
                db.update("DELETE FROM session_skill_state WHERE session_id=? AND name=?", sessionId, key);
            } else {
                db.update("INSERT INTO session_skill_state(session_id, name, enabled) VALUES(?, ?, ?) "
                        + "ON CONFLICT(session_id, name) DO UPDATE SET enabled=excluded.enabled",
                        sessionId, key, on ? 1 : 0);
            }
        } catch (Exception e) {
            log.warn("[skills] could not persist session_skill_state: " + e.getMessage());
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
        return indexAddendum(null);
    }

    public synchronized String indexAddendum(String sessionId) {
        List<SkillLibrary.Skill> active = enabledSkillsFor(sessionId);
        if (!enabled || active.isEmpty()) return "";
        return "\n\n--- Available skills (call load_skill with the name to load full instructions) ---\n"
                + SkillLibrary.index(active);
    }

    /** When skills.auto-load is on, the best-matching skill's body for a query (or ""). */
    public synchronized String autoLoadAddendum(String query) {
        return autoLoadAddendum(query, null);
    }

    public synchronized String autoLoadAddendum(String query, String sessionId) {
        List<SkillLibrary.Skill> active = enabledSkillsFor(sessionId);
        if (!enabled || !autoLoad || active.isEmpty()) return "";
        List<SkillLibrary.Skill> top = SkillLibrary.select(active, query, 1);
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

    // ---- /skills + direct /<skill-name> invocation --------------------------

    /** True for the built-in {@code /skills} listing command. */
    public synchronized boolean isSkillsCommand(String msg) {
        return msg != null && msg.trim().equals("/skills");
    }

    /** Human-readable {@code /skills} listing for this session (effective enabled-state). */
    public synchronized String skillsReport(String sessionId) {
        if (!enabled) return "Skills are disabled (set skills.enabled=true).";
        return SkillInvocation.renderList(listForUi(sessionId));
    }

    /** The name of the enabled skill a {@code /<name> ...} message invokes, or null. For the trace. */
    public synchronized String invokedSkillName(String msg, String sessionId) {
        if (!enabled) return null;
        SkillInvocation.Parsed p = SkillInvocation.parse(msg);
        if (p == null || SkillInvocation.isReserved(p.name())) return null;
        for (SkillLibrary.Skill s : enabledSkillsFor(sessionId)) {
            if (s.name().equalsIgnoreCase(p.name())) return s.name();
        }
        return null;
    }

    /**
     * If {@code msg} is {@code /<skill-name> [args]} for an enabled skill, return its body with
     * {@code $ARGUMENTS} substituted; otherwise null (so the caller falls back to normal handling).
     */
    public synchronized String expandInvocation(String msg, String sessionId) {
        if (!enabled) return null;
        SkillInvocation.Parsed p = SkillInvocation.parse(msg);
        if (p == null || SkillInvocation.isReserved(p.name())) return null;
        for (SkillLibrary.Skill s : enabledSkillsFor(sessionId)) {
            if (s.name().equalsIgnoreCase(p.name())) {
                String out = SkillInvocation.substitute(s.body(), p.args());
                if (s.allowedTools() != null && !s.allowedTools().isEmpty()) {
                    out = out + "\n\n(For this skill, prefer these tools only: "
                            + String.join(", ", s.allowedTools()) + ".)";
                }
                return out;
            }
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
            String want = args.get("name") == null ? "" : String.valueOf(args.get("name"));
            SkillLibrary.Skill s = byName(want);
            if (s == null) {
                return "skill not found. Available: "
                        + (enabledSkills().isEmpty() ? "(none)" : SkillLibrary.index(enabledSkills()));
            }
            if (isDisabled(s.name())) return "skill '" + s.name() + "' is disabled";
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

    /** Save a skill approved from a member request; returns a status string. */
    public synchronized String saveApproved(String rawName, String description, String body) {
        return save(rawName, description, body);
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
