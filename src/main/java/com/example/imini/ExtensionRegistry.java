package com.example.imini;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers and hosts in-process {@link Extension}s. Spring injects every bean implementing {@link
 * Extension} (an empty list when none exist — so with no extensions the harness is byte-identical to
 * before). Contributions are collected ONCE, lazily, and cached; each extension call is isolated so a
 * throwing extension is logged and skipped rather than crashing startup.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>collect {@link Extension#tools}, {@link Extension#agents}, {@link Extension#commands} across all
 *       extensions, de-duplicating by name AMONG extensions (first wins, later dups warned);
 *   <li>{@link #emit(LoopEvent)} — fan a loop event out to every extension's {@link Extension#onEvent};
 *   <li>a master kill-switch {@code extensions.enabled} (default true) that makes every collection empty
 *       and {@link #emit} a no-op;
 *   <li>{@link #diagnostics()} for {@code GET /admin/extensions}.
 * </ul>
 *
 * <p>Collision with a CORE tool name (built-in / MCP) is resolved by {@link ToolRegistry}, which owns
 * the live tool map and skips an extension tool whose name is already taken; this class only guarantees
 * extensions don't collide with each other.
 */
@Component
public class ExtensionRegistry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExtensionRegistry.class);

    private final List<Extension> extensions;
    private final Environment env;
    private final boolean enabled;
    private final Path workspaceRoot = Path.of("").toAbsolutePath().normalize();

    // Collected contributions (computed once by ensureLoaded()).
    private volatile boolean loaded = false;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<AgentLibrary.AgentDef> agents = new ArrayList<>();
    private final Map<String, Extension.Command> commands = new LinkedHashMap<>();
    // Per-extension summary for the admin view: name -> {tools, agents, commands}.
    private final Map<String, Map<String, List<String>>> byExtension = new LinkedHashMap<>();

    @Autowired
    public ExtensionRegistry(List<Extension> extensions, Environment env,
                             @Value("${extensions.enabled:true}") boolean enabled) {
        this.extensions = extensions == null ? List.of() : extensions;
        this.env = env == null ? new StandardEnvironment() : env;
        this.enabled = enabled;
    }

    /** Test-friendly constructor: no Spring Environment needed (properties resolve to their defaults). */
    ExtensionRegistry(List<Extension> extensions, boolean enabled) {
        this(extensions, new StandardEnvironment(), enabled);
    }

    private ExtensionContext contextFor(Extension ext) {
        return new ExtensionContext(workspaceRoot, env::getProperty, ext.name());
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        if (enabled) {
            for (Extension ext : extensions) {
                String extName = safeName(ext);
                Map<String, List<String>> summary = new LinkedHashMap<>();
                summary.put("tools", new ArrayList<>());
                summary.put("agents", new ArrayList<>());
                summary.put("commands", new ArrayList<>());
                ExtensionContext ctx = contextFor(ext);
                collectTools(ext, extName, ctx, summary);
                collectAgents(ext, extName, ctx, summary);
                collectCommands(ext, extName, ctx, summary);
                byExtension.put(extName, summary);
            }
            log.info("[extensions] loaded " + extensions.size() + " extension(s): " + byExtension.keySet()
                    + " -> tools=" + tools.keySet() + " agents=" + agentNames() + " commands=" + commands.keySet());
        } else if (!extensions.isEmpty()) {
            log.info("[extensions] disabled (extensions.enabled=false); ignoring "
                    + extensions.size() + " extension bean(s).");
        }
        loaded = true;
    }

    private void collectTools(Extension ext, String extName, ExtensionContext ctx,
                              Map<String, List<String>> summary) {
        for (Tool t : safeList(() -> ext.tools(ctx), extName, "tools")) {
            if (t == null || t.name == null || t.name.isBlank()) continue;
            if (tools.containsKey(t.name)) {
                log.warn("[extensions] duplicate tool name \"" + t.name + "\" from " + extName
                        + " ignored (already contributed by another extension).");
                continue;
            }
            tools.put(t.name, t);
            summary.get("tools").add(t.name);
        }
    }

    private void collectAgents(Extension ext, String extName, ExtensionContext ctx,
                               Map<String, List<String>> summary) {
        Set<String> have = new LinkedHashSet<>(agentNames());
        for (AgentLibrary.AgentDef a : safeList(() -> ext.agents(ctx), extName, "agents")) {
            if (a == null || a.name() == null || a.name().isBlank()) continue;
            if (have.contains(a.name().toLowerCase(java.util.Locale.ROOT))) {
                log.warn("[extensions] duplicate agent name \"" + a.name() + "\" from " + extName + " ignored.");
                continue;
            }
            agents.add(a);
            have.add(a.name().toLowerCase(java.util.Locale.ROOT));
            summary.get("agents").add(a.name());
        }
    }

    private void collectCommands(Extension ext, String extName, ExtensionContext ctx,
                                 Map<String, List<String>> summary) {
        for (Extension.Command c : safeList(() -> ext.commands(ctx), extName, "commands")) {
            if (c == null || c.name() == null || c.name().isBlank()) continue;
            if (commands.containsKey(c.name())) {
                log.warn("[extensions] duplicate command \"/" + c.name() + "\" from " + extName + " ignored.");
                continue;
            }
            commands.put(c.name(), c);
            summary.get("commands").add(c.name());
        }
    }

    /** All extension-contributed tools (empty when disabled or none). Insertion order preserved. */
    public List<Tool> tools() {
        ensureLoaded();
        return new ArrayList<>(tools.values());
    }

    /** All extension-contributed subagents. */
    public List<AgentLibrary.AgentDef> agents() {
        ensureLoaded();
        return new ArrayList<>(agents);
    }

    /** All extension-contributed slash commands, by name. */
    public Map<String, Extension.Command> commands() {
        ensureLoaded();
        return new LinkedHashMap<>(commands);
    }

    /** Fan a loop event out to every extension. Isolated per extension; a no-op when disabled. */
    public void emit(LoopEvent event) {
        if (!enabled || extensions.isEmpty() || event == null) return;
        for (Extension ext : extensions) {
            try {
                ext.onEvent(event, contextFor(ext));
            } catch (Exception e) {
                log.warn("[extensions] " + safeName(ext) + ".onEvent threw for " + event.type()
                        + " (ignored): " + e.getMessage());
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** For {@code GET /admin/extensions}: what loaded and what each extension contributed. */
    public Map<String, Object> diagnostics() {
        ensureLoaded();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("count", byExtension.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> e : byExtension.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("tools", e.getValue().get("tools"));
            row.put("agents", e.getValue().get("agents"));
            row.put("commands", e.getValue().get("commands"));
            list.add(row);
        }
        out.put("extensions", list);
        return out;
    }

    // ---- helpers ----

    private List<String> agentNames() {
        List<String> out = new ArrayList<>();
        for (AgentLibrary.AgentDef a : agents) out.add(a.name());
        return out;
    }

    private static String safeName(Extension ext) {
        try {
            String n = ext.name();
            return (n == null || n.isBlank()) ? ext.getClass().getSimpleName() : n;
        } catch (Exception e) {
            return ext.getClass().getSimpleName();
        }
    }

    private <T> List<T> safeList(java.util.function.Supplier<List<T>> s, String extName, String kind) {
        try {
            List<T> r = s.get();
            return r == null ? List.of() : r;
        } catch (Exception e) {
            log.warn("[extensions] " + extName + "." + kind + "() threw (ignored): " + e.getMessage());
            return List.of();
        }
    }
}
