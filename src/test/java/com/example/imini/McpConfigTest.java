package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for MCP hot-reload: the pure config diff (add/remove/restart plan), spec equality
 * (command/args/env/transport/url), and the derived stop/start (registry-delta) lists. JSON parsing of
 * mcp.json gates on a real mapper via {@code IntegrationGate("json", …)}.
 */
public class McpConfigTest {

  private static McpConfig.ServerSpec stdio(String cmd, String... args) {
    return new McpConfig.ServerSpec("stdio", cmd, List.of(args), Map.of(), "");
  }

  @Test
  void diffClassifiesAddedRemovedRestartedUnchanged() {
    Map<String, McpConfig.ServerSpec> running = Map.of(
        "keep", stdio("npx", "keep"),
        "change", stdio("npx", "old"),
        "drop", stdio("npx", "drop"));
    Map<String, McpConfig.ServerSpec> desired = Map.of(
        "keep", stdio("npx", "keep"),
        "change", stdio("npx", "new"), // args differ -> restarted
        "add", stdio("npx", "add"));
    McpConfig.ReloadPlan plan = McpConfig.diff(running, desired);
    assertEquals(List.of("add"), plan.added());
    assertEquals(List.of("drop"), plan.removed());
    assertEquals(List.of("change"), plan.restarted());
    assertEquals(List.of("keep"), plan.unchanged());
    assertFalse(plan.isNoOp());
  }

  @Test
  void identicalConfigIsNoOp() {
    Map<String, McpConfig.ServerSpec> a = Map.of("fs", stdio("npx", "-y", "server"));
    assertTrue(McpConfig.diff(a, a).isNoOp(), "same config -> no-op reload");
    assertTrue(McpConfig.diff(Map.of(), Map.of()).isNoOp(), "empty -> empty is a no-op");
  }

  @Test
  void specEqualityConsidersCommandArgsEnvTransportUrl() {
    assertEquals(stdio("npx", "a"), stdio("npx", "a"), "same command+args equal");
    assertFalse(stdio("npx", "a").equals(stdio("npx", "b")), "different args differ");
    McpConfig.ServerSpec e1 = new McpConfig.ServerSpec("stdio", "npx", List.of(), Map.of("K", "1"), "");
    McpConfig.ServerSpec e2 = new McpConfig.ServerSpec("stdio", "npx", List.of(), Map.of("K", "2"), "");
    assertFalse(e1.equals(e2), "different env differs -> triggers restart");
  }

  @Test
  void stopAndStartListsAreDerivedPurely() {
    McpConfig.ReloadPlan plan = new McpConfig.ReloadPlan(
        List.of("add"), List.of("drop"), List.of("change"), List.of("keep"));
    // Tools for removed + changed servers must be pruned; added + changed must be (re)launched.
    assertEquals(List.of("change", "drop"), plan.serversToStop());
    assertEquals(List.of("add", "change"), plan.serversToStart());
  }

  @Test
  void specFromConfMapIsPure() {
    McpConfig.ServerSpec s = McpConfig.spec(Map.of(
        "command", "npx", "args", List.of("-y", "srv"), "env", Map.of("TOKEN", "x")));
    assertEquals("npx", s.command());
    assertEquals(List.of("-y", "srv"), s.args());
    assertEquals("x", s.env().get("TOKEN"));
    assertEquals("stdio", s.transport(), "defaults to stdio");
  }

  @Test
  void toolCountsByServerIsPure() {
    List<String> toolNames = List.of("one_echo", "one_read_resource", "two_echo", "reload_mcp", "grep");
    Map<String, Integer> counts = McpConfig.toolCountsByServer(toolNames, List.of("one", "two"));
    assertEquals(Integer.valueOf(2), counts.get("one"), "one contributes 2 tools");
    assertEquals(Integer.valueOf(1), counts.get("two"), "two contributes 1 tool");
    assertEquals("one_", McpConfig.toolPrefix("one"));
  }

  @Test
  void republishMcpSwapsMcpToolsKeepingBuiltins() {
    java.util.Map<String, Tool> live = new java.util.LinkedHashMap<>();
    live.put("grep", tool("grep"));            // a built-in, must survive
    live.put("one_echo", tool("one_echo"));    // a stale MCP tool from a previous publish
    java.util.Set<String> old = new java.util.LinkedHashSet<>(List.of("one_echo"));

    java.util.Set<String> now = ToolRegistry.republishMcp(live, old, List.of(tool("two_echo"), tool("two_ping")));
    assertTrue(live.containsKey("grep"), "built-in preserved");
    assertFalse(live.containsKey("one_echo"), "stale MCP tool removed");
    assertTrue(live.containsKey("two_echo") && live.containsKey("two_ping"), "current MCP tools added");
    assertEquals(java.util.Set.of("two_echo", "two_ping"), now, "returns the new MCP name set");
  }

  private static Tool tool(String name) {
    return new Tool(name, "", Map.of("type", "object", "properties", Map.of()), false, false, a -> "");
  }

  // ---- JSON parsing of mcp.json (gated on a real mapper) ----

  @Test
  void parseSpecsReadsMcpJson() throws Exception {
    if (!IntegrationGate.proceed("json", "McpConfigTest.parse", JsonProbe.realMapperAvailable())) return;
    Map<String, McpConfig.ServerSpec> specs = McpConfig.parseSpecs(new ObjectMapper(), fixture("mcp/mcp.json"));
    assertEquals(2, specs.size(), "two servers: " + specs.keySet());
    assertEquals("npx", specs.get("fs").command());
    assertTrue(specs.get("fs").args().contains("/tmp"), "args parsed");
    assertEquals("http", specs.get("web").transport());
    assertEquals("https://mcp.example.org/sse", specs.get("web").url());
  }

  private static String fixture(String name) throws IOException {
    try (InputStream in = McpConfigTest.class.getResourceAsStream("/" + name)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
