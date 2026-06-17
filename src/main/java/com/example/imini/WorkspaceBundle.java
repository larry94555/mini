package com.example.imini;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure model + summary for a whole-workspace bundle: a single artifact combining a plugin pack (all
 * skills, agents, and commands) with the durable application settings. Lets you back up or clone an entire
 * imini setup in one file, beyond the per-session export or a single plugin pack. The JSON build/parse and
 * file I/O live in {@link WorkspaceService}; this class holds the constants and a dependency-free summary
 * so the shape is documented and unit-testable.
 */
public final class WorkspaceBundle {

    private WorkspaceBundle() {}

    public static final String FORMAT = "imini-workspace/1";

    /**
     * A human/programmatic summary of a bundle's contents: counts of skills/agents/commands (from the
     * pack) and the number of settings. Pure -- given the piece counts, returns an ordered map.
     */
    public static Map<String, Object> summarize(int skills, int agents, int commands, int settings) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", FORMAT);
        out.put("skills", Math.max(0, skills));
        out.put("agents", Math.max(0, agents));
        out.put("commands", Math.max(0, commands));
        out.put("settings", Math.max(0, settings));
        out.put("entries", Math.max(0, skills) + Math.max(0, agents) + Math.max(0, commands));
        return out;
    }
}
