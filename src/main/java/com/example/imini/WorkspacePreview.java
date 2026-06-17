package com.example.imini;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure classification helpers for a whole-workspace import preview (dry-run): decide, without touching
 * the filesystem, whether each setting is new/changed/unchanged. The pack-entry create/overwrite check
 * needs the filesystem and lives in {@link PluginService}; this keeps the settings logic and the summary
 * deterministic and unit-testable.
 */
public final class WorkspacePreview {

    private WorkspacePreview() {}

    /** "new" if there is no current value, "unchanged" if equal, else "changed". */
    public static String classifySetting(String current, String incoming) {
        if (current == null) return "new";
        return Objects.equals(current, incoming) ? "unchanged" : "changed";
    }

    /** Ordered counts for a preview report. */
    public static Map<String, Object> summarize(int create, int overwrite, int blocked,
                                                 int settingsNew, int settingsChanged, int settingsUnchanged) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> packs = new LinkedHashMap<>();
        packs.put("create", Math.max(0, create));
        packs.put("overwrite", Math.max(0, overwrite));
        packs.put("blocked", Math.max(0, blocked));
        out.put("pack", packs);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("new", Math.max(0, settingsNew));
        settings.put("changed", Math.max(0, settingsChanged));
        settings.put("unchanged", Math.max(0, settingsUnchanged));
        out.put("settings", settings);
        out.put("dryRun", true);
        return out;
    }
}
