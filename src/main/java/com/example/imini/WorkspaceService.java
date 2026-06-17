package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export and import a whole-workspace bundle: the plugin pack (skills + agents + commands) plus the
 * durable app settings, as one JSON document. Reuses {@link PluginService} for the pack (so install stays
 * workspace-confined and path-sanitized) and {@link SettingsStore} for settings. Admin-gated at the
 * controller.
 */
@Component
public class WorkspaceService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceService.class);

    private final PluginService plugins;
    private final SettingsStore settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkspaceService(PluginService plugins, SettingsStore settings) {
        this.plugins = plugins;
        this.settings = settings;
    }

    /** Build the bundle JSON: {format, exportedAt, pack:{...}, settings:{k:v}}. */
    public String exportJson(String name, String description) throws Exception {
        PluginPack.Pack pack = plugins.exportPack(name, "1", description);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", WorkspaceBundle.FORMAT);
        bundle.put("exportedAt", java.time.Instant.now().toString());
        bundle.put("pack", pack);
        bundle.put("settings", settings.all());
        return mapper.writeValueAsString(bundle);
    }

    /** A summary of what the current workspace would export (counts), without serializing. */
    public Map<String, Object> summary() {
        PluginPack.Pack pack = plugins.exportPack("workspace", "1", "");
        int skills = 0, agents = 0, commands = 0;
        for (PluginPack.Entry e : pack.entries()) {
            switch (e.type()) {
                case "skill" -> skills++;
                case "agent" -> agents++;
                case "command" -> commands++;
                default -> { }
            }
        }
        return WorkspaceBundle.summarize(skills, agents, commands, settings.all().size());
    }

    /**
     * Import a bundle: install its pack (workspace-confined, honoring {@code overwrite}) and apply its
     * settings. Returns a report of what was installed/skipped and which settings were applied. Unknown
     * top-level shapes are rejected.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importBundle(String bundleJson, boolean overwrite) {
        if (bundleJson == null || bundleJson.isBlank()) return Map.of("error", "empty bundle");
        Map<String, Object> root;
        try {
            root = mapper.readValue(bundleJson, Map.class);
        } catch (Exception e) {
            return Map.of("error", "invalid JSON: " + e.getMessage());
        }
        if (root == null || !root.containsKey("pack")) {
            return Map.of("error", "not a workspace bundle (missing 'pack')");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        // 1) install the pack via the existing, confined installer
        try {
            String packJson = mapper.writeValueAsString(root.get("pack"));
            Map<String, Object> installed = plugins.install(packJson, overwrite);
            out.put("pack", installed);
        } catch (Exception e) {
            return Map.of("error", "pack install failed: " + e.getMessage());
        }
        // 2) apply settings
        List<String> applied = new ArrayList<>();
        Object s = root.get("settings");
        if (s instanceof Map<?, ?> settingsMap) {
            for (Map.Entry<?, ?> en : settingsMap.entrySet()) {
                String k = String.valueOf(en.getKey());
                Object v = en.getValue();
                if (k != null && !k.isBlank() && v != null) {
                    settings.setString(k, String.valueOf(v));
                    applied.add(k);
                }
            }
        }
        out.put("settingsApplied", applied);
        log.info("[workspace] imported bundle: settings applied=" + applied.size());
        return out;
    }
}
