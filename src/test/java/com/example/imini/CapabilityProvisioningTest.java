package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline coverage for the pure capability-provisioning view (links a tool-select stage to reloaded MCP servers). */
public class CapabilityProvisioningTest {

  @Test
  void linksToolSelectSkillToReloadedServers() {
    Map<String, List<String>> applied = Map.of("tool-select", List.of("tool-builder"));
    Map<String, Object> reload = Map.of("added", List.of("weather"), "restarted", List.of(), "removed", List.of());
    Map<String, Object> view = CapabilityProvisioning.view(applied, reload);
    assertEquals(List.of("tool-builder"), view.get("tool_select_skills"));
    assertEquals(List.of("weather"), view.get("servers_provisioned"));
    assertEquals(Boolean.TRUE, view.get("provisioned"), "stage applied a skill AND a server was reloaded");
  }

  @Test
  void serversProvisionedCombinesAddedAndRestarted() {
    Map<String, Object> reload = Map.of("added", List.of("a"), "restarted", List.of("b"));
    assertEquals(List.of("a", "b"), CapabilityProvisioning.serversProvisioned(reload));
    assertTrue(CapabilityProvisioning.serversProvisioned(null).isEmpty(), "null-safe");
  }

  @Test
  void notProvisionedWhenStageFiredButNoReload() {
    Map<String, Object> view = CapabilityProvisioning.view(
        Map.of("tool-select", List.of("tool-builder")), Map.of("added", List.of(), "restarted", List.of()));
    assertFalse((Boolean) view.get("provisioned"), "a skill fired but nothing was reloaded");
  }

  @Test
  void notProvisionedWhenServerReloadedButStageDidNotFire() {
    Map<String, Object> view = CapabilityProvisioning.view(Map.of(), Map.of("added", List.of("x")));
    assertFalse((Boolean) view.get("provisioned"), "a server reloaded but no tool-select skill applied");
    assertTrue(((List<?>) view.get("tool_select_skills")).isEmpty());
  }
}
