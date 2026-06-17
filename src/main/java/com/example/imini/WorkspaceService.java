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
     * Dry-run an import: report what installing the bundle WOULD do -- which pack entries would be created
     * vs overwritten vs blocked, and which settings are new/changed/unchanged -- writing nothing.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> previewBundle(String bundleJson) {
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
        // pack: classify via the (filesystem-aware) installer preview
        List<String> create = List.of(), overwrite = List.of(), blocked = List.of();
        try {
            String packJson = mapper.writeValueAsString(root.get("pack"));
            Map<String, Object> pv = plugins.previewInstall(packJson);
            if (pv.containsKey("error")) return Map.of("error", "pack preview failed: " + pv.get("error"));
            create = (List<String>) pv.getOrDefault("create", List.of());
            overwrite = (List<String>) pv.getOrDefault("overwrite", List.of());
            blocked = (List<String>) pv.getOrDefault("blocked", List.of());
            Map<String, Object> packDetail = new LinkedHashMap<>();
            packDetail.put("create", create);
            packDetail.put("overwrite", overwrite);
            packDetail.put("blocked", blocked);
            out.put("packDetail", packDetail);
        } catch (Exception e) {
            return Map.of("error", "pack preview failed: " + e.getMessage());
        }
        // settings: classify each as new/changed/unchanged against current values
        int sNew = 0, sChanged = 0, sUnchanged = 0;
        List<String> changedKeys = new ArrayList<>();
        Object s = root.get("settings");
        if (s instanceof Map<?, ?> settingsMap) {
            for (Map.Entry<?, ?> en : settingsMap.entrySet()) {
                String k = String.valueOf(en.getKey());
                if (k == null || k.isBlank()) continue;
                String incoming = en.getValue() == null ? "" : String.valueOf(en.getValue());
                String current = settings.getString(k, null);
                String cls = WorkspacePreview.classifySetting(current, incoming);
                switch (cls) {
                    case "new" -> { sNew++; changedKeys.add(k + " (new)"); }
                    case "changed" -> { sChanged++; changedKeys.add(k + " (changed)"); }
                    default -> sUnchanged++;
                }
            }
        }
        out.put("settingsDetail", changedKeys);
        out.put("summary", WorkspacePreview.summarize(create.size(), overwrite.size(), blocked.size(),
                sNew, sChanged, sUnchanged));
        return out;
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
