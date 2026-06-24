package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure "capability provisioning" view: links the plan-lifecycle stage that requested a tool (typically
 * {@code tool-select} applying the {@code tool-builder} skill) to the MCP server(s) that were (re)loaded, so
 * the end-to-end "a step needed a capability -> a tool was provisioned" story is observable in one place. No
 * I/O — it derives the view from the lifecycle last-applied map and the last-reload summary, so it is fully
 * unit-testable with fakes.
 */
public final class CapabilityProvisioning {

  private CapabilityProvisioning() {}

  /** The token a lifecycle stage uses to request a provisioned tool. */
  public static final String TOOL_SELECT_STAGE = PlanLifecycle.Stage.TOOL_SELECT.id();

  /**
   * Build the combined view. {@code lastApplied} is stage-id -> applied skill names (from
   * {@code SkillService.lifecycleLastApplied()}); {@code lastReload} is the MCP reload summary (from
   * {@code McpManager.diagnostics().get("last_reload")}, may be null). Pure.
   */
  public static Map<String, Object> view(Map<String, List<String>> lastApplied,
                                          Map<String, Object> lastReload) {
    Map<String, Object> out = new LinkedHashMap<>();

    List<String> toolSelectSkills = lastApplied == null ? List.of()
        : lastApplied.getOrDefault(TOOL_SELECT_STAGE, List.of());
    out.put("tool_select_skills", new ArrayList<>(toolSelectSkills));

    List<String> provisioned = serversProvisioned(lastReload);
    out.put("servers_provisioned", provisioned);

    // The capability was provisioned end-to-end when the tool-select stage applied a skill AND a reload
    // brought new/changed servers online in the same cycle.
    out.put("provisioned", !toolSelectSkills.isEmpty() && !provisioned.isEmpty());
    return out;
  }

  /** Pure: the servers brought online by the last reload (added + restarted), from the reload summary. */
  @SuppressWarnings("unchecked")
  public static List<String> serversProvisioned(Map<String, Object> lastReload) {
    List<String> out = new ArrayList<>();
    if (lastReload == null) {
      return out;
    }
    Object added = lastReload.get("added");
    Object restarted = lastReload.get("restarted");
    if (added instanceof List<?> a) {
      for (Object o : a) out.add(String.valueOf(o));
    }
    if (restarted instanceof List<?> r) {
      for (Object o : r) out.add(String.valueOf(o));
    }
    return out;
  }
}
