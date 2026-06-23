package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP hot-reload against the bundled stub server, exercising the production {@link McpManager#reload}
 * path (read mcp.json -> diff -> stop/prune -> launch/discover -> reload hook) and proving the LIVE tool set
 * is republished via the same {@link ToolRegistry#republishMcp} the production hook uses. Gated on the
 * {@code node} family (needs a child process) and {@code json} (discovery parses JSON-RPC), so it self-skips
 * offline and runs in CI. The pure config-diff/registry-delta tests live in {@link McpConfigTest}.
 */
public class McpHotReloadIntegrationTest {

    @Test
    void hotReloadAddsRemovesAndIsIdempotentAgainstStub() throws Exception {
        if (!IntegrationGate.proceed("node", "McpHotReloadIntegrationTest.hotReload", McpStubFixture.available())) return;
        if (!IntegrationGate.proceed("json", "McpHotReloadIntegrationTest.hotReload", JsonProbe.realMapperAvailable())) return;

        Path stub = McpStubFixture.stubScript();
        Path cfg = Path.of("mcp.json");
        byte[] backup = Files.exists(cfg) ? Files.readAllBytes(cfg) : null;

        McpManager mcp = new McpManager();
        setTimeout(mcp, 30);

        // Wire the production reload hook: republish the MCP tools into a live tool map that also holds a
        // built-in sentinel, exactly as ToolRegistry does after a reload.
        Map<String, Tool> live = new LinkedHashMap<>();
        live.put("grep", builtin("grep")); // a built-in that must survive every reload
        Set<String>[] mcpNames = new Set[]{ new LinkedHashSet<>() };
        mcp.setReloadHook(() -> mcpNames[0] = ToolRegistry.republishMcp(live, mcpNames[0], mcp.tools()));

        try {
            // (a) one server -> its tools present in both the manager and the live set
            writeConfig(cfg, stub, "one");
            Map<String, Object> r1 = mcp.reload();
            assertTrue(((List<?>) r1.get("added")).contains("one"), "first reload adds 'one': " + r1);
            assertTrue(hasTool(mcp, "one_echo"), "manager has one_echo: " + toolNames(mcp));
            assertTrue(live.containsKey("one_echo"), "live set has one_echo");
            assertTrue(live.containsKey("grep"), "built-in survived");

            // (b) add a second server -> new tools appear, first server's remain
            writeConfig(cfg, stub, "one", "two");
            Map<String, Object> r2 = mcp.reload();
            assertEquals(List.of("two"), r2.get("added"), "second reload adds only 'two': " + r2);
            assertTrue(live.containsKey("one_echo") && live.containsKey("two_echo"),
                    "both servers' tools live: " + live.keySet());

            // (c) remove the first server -> its tools are pruned from the live set
            writeConfig(cfg, stub, "two");
            Map<String, Object> r3 = mcp.reload();
            assertTrue(((List<?>) r3.get("removed")).contains("one"), "third reload removes 'one': " + r3);
            assertFalse(hasTool(mcp, "one_echo"), "one_echo pruned from manager");
            assertFalse(live.containsKey("one_echo"), "one_echo pruned from live set");
            assertTrue(live.containsKey("two_echo"), "two_echo still live");
            assertTrue(live.containsKey("grep"), "built-in still survives");

            // (d) reload with no change -> no-op, live tool set byte-identical
            List<String> before = new ArrayList<>(live.keySet());
            Map<String, Object> r4 = mcp.reload();
            assertEquals(Boolean.TRUE, r4.get("no_op"), "unchanged config -> no-op: " + r4);
            assertEquals(before, new ArrayList<>(live.keySet()), "idempotent: live tool set unchanged");

            // diagnostics report a per-server tool count (Alt 1)
            Object byServer = mcp.diagnostics().get("tools_by_server");
            assertTrue(byServer instanceof Map && ((Map<?, ?>) byServer).containsKey("two"),
                    "diagnostics expose per-server tool counts: " + byServer);
        } finally {
            if (backup != null) {
                Files.write(cfg, backup);
            } else {
                Files.deleteIfExists(cfg);
            }
        }
    }

    private static void writeConfig(Path cfg, Path stub, String... servers) throws Exception {
        StringBuilder sb = new StringBuilder("{\"mcpServers\":{");
        for (int i = 0; i < servers.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(servers[i]).append("\":{\"command\":\"node\",\"args\":[\"")
              .append(stub.toString().replace("\\", "\\\\")).append("\"]}");
        }
        sb.append("}}");
        Files.write(cfg, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hasTool(McpManager mcp, String name) {
        return mcp.tools().stream().anyMatch(t -> t.name.equals(name));
    }

    private static List<String> toolNames(McpManager mcp) {
        return mcp.tools().stream().map(t -> t.name).toList();
    }

    private static Tool builtin(String name) {
        return new Tool(name, "", Map.of("type", "object", "properties", Map.of()), false, false, a -> "");
    }

    private static void setTimeout(McpManager mcp, int seconds) throws Exception {
        java.lang.reflect.Field f = McpManager.class.getDeclaredField("toolTimeoutSeconds");
        f.setAccessible(true);
        f.setInt(mcp, seconds);
    }
}
