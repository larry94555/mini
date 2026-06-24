package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that the {@code tool-builder} flow reaches MCP hot-reload: a plan step that needs a
 * capability not covered by built-ins triggers the {@code tool-select} lifecycle stage (which applies the
 * {@code tool-builder} skill), and the production {@code reload_mcp} path then brings the "installed" tool's
 * (the bundled MCP stub server) tools into the LIVE tool set via the same {@link ToolRegistry#republishMcp}
 * hook used in production. A control run without the binding surfaces no tool-builder guidance.
 *
 * <p>Gated on {@code node} (the stub is a node child process) and {@code json} (discovery parses JSON-RPC),
 * so it self-skips offline. The pure pieces are covered by {@link CapabilityProvisioningTest},
 * {@link PlanLifecycleTest}, and {@link McpConfigTest}.
 */
public class ToolBuilderProvisioningIntegrationTest {

    static final String MARKER = "TOOLBUILDER_MARKER_9X";

    @Test
    void toolSelectAppliesToolBuilderThenReloadProvisionsTheTool() throws Exception {
        if (!IntegrationGate.proceed("node", "ToolBuilderProvisioningIntegrationTest.flow", McpStubFixture.available())) return;
        if (!IntegrationGate.proceed("json", "ToolBuilderProvisioningIntegrationTest.flow", JsonProbe.realMapperAvailable())) return;

        Path ws = Files.createTempDirectory("imini-toolbuilder-ws-");
        Path stub = McpStubFixture.stubScript();
        Path cfg = Path.of("mcp.json");
        byte[] backup = Files.exists(cfg) ? Files.readAllBytes(cfg) : null;

        // A real SkillService pointed at a temp skills dir holding a deterministic tool-builder skill.
        Path skillDir = ws.resolve("skills").resolve("tool-builder");
        Files.createDirectories(skillDir);
        Files.write(skillDir.resolve("SKILL.md"), ("""
                ---
                name: tool-builder
                description: research and install a better-fit local MCP tool, with permission
                when_to_use: when a step needs a capability no built-in tool covers
                ---
                %s : research an installable MCP tool, get permission, install it, add it to mcp.json, then call reload_mcp.
                """.formatted(MARKER)).getBytes(StandardCharsets.UTF_8));

        SkillService skills = new SkillService(new Sandbox(), null);
        set(skills, SkillService.class, "enabled", true);
        set(skills, SkillService.class, "skillsDir", "skills");
        set(skills, SkillService.class, "maxBody", 4000);
        setSandboxRoot(skills, ws);

        // A real McpManager whose reload hook republishes MCP tools into a live tool map (a built-in survives).
        McpManager mcp = new McpManager();
        setTimeout(mcp, 30);
        Map<String, Tool> live = new LinkedHashMap<>();
        live.put("grep", builtin("grep"));
        Set<String>[] mcpNames = new Set[]{ new LinkedHashSet<>() };
        mcp.setReloadHook(() -> mcpNames[0] = ToolRegistry.republishMcp(live, mcpNames[0], mcp.tools()));

        try {
            // ---- BOUND run: a step needs a capability -> tool-select applies tool-builder ----
            set(skills, SkillService.class, "lifecycleConfig", "tool-select=tool-builder");
            skills.reload();
            String step = "This step needs a weather capability that no built-in tool provides.";
            String addendum = skills.lifecycleAddendum(PlanLifecycle.Stage.TOOL_SELECT, step, "sess-bound");

            // (a) the tool-select stage applied the tool-builder skill (observable in diagnostics)
            assertTrue(addendum.contains(MARKER), "tool-builder guidance injected at tool-select: " + addendum);
            assertTrue(skills.lifecycleLastApplied().getOrDefault("tool-select", List.of()).contains("tool-builder"),
                    "diagnostics record tool-select applied tool-builder: " + skills.lifecycleLastApplied());

            // ...the skill then "installs" a tool by adding the stub to mcp.json and calling reload_mcp.
            writeConfig(cfg, stub, "weather");
            mcp.reload();

            // (b) the production reload made the stub's tools appear in the LIVE tool set
            assertTrue(live.containsKey("weather_echo"), "reload provisioned the tool into the live set: " + live.keySet());
            assertTrue(live.containsKey("grep"), "built-in survived the reload");

            // combined capability-provisioning view ties the two together
            @SuppressWarnings("unchecked")
            Map<String, Object> lastReload = (Map<String, Object>) mcp.diagnostics().get("last_reload");
            Map<String, Object> view = CapabilityProvisioning.view(skills.lifecycleLastApplied(), lastReload);
            assertTrue((Boolean) view.get("provisioned"), "capability provisioned end-to-end: " + view);
            assertTrue(((List<?>) view.get("servers_provisioned")).contains("weather"), "weather server provisioned: " + view);

            // ---- CONTROL run: no binding -> no tool-builder guidance surfaces ----
            // A fresh plan run resets the per-run lifecycle record (as AgentLoop.runPlan does at its start).
            set(skills, SkillService.class, "lifecycleConfig", "");
            skills.reload();
            skills.resetLifecycleRecord();
            String controlAddendum = skills.lifecycleAddendum(PlanLifecycle.Stage.TOOL_SELECT, step, "sess-control");
            assertFalse(controlAddendum.contains(MARKER), "control run surfaces no tool-builder guidance: " + controlAddendum);
            assertFalse(skills.lifecycleLastApplied().containsKey("tool-select"),
                    "control run records no tool-select application: " + skills.lifecycleLastApplied());
        } finally {
            if (backup != null) Files.write(cfg, backup); else Files.deleteIfExists(cfg);
            deleteTree(ws);
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

    private static Tool builtin(String name) {
        return new Tool(name, "", Map.of("type", "object", "properties", Map.of()), false, false, a -> "");
    }

    private static void set(Object target, Class<?> cls, String field, Object value) throws Exception {
        java.lang.reflect.Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void setSandboxRoot(SkillService skills, Path root) throws Exception {
        java.lang.reflect.Field sf = SkillService.class.getDeclaredField("sandbox");
        sf.setAccessible(true);
        Object sandbox = sf.get(skills);
        java.lang.reflect.Field rf = Sandbox.class.getDeclaredField("root");
        rf.setAccessible(true);
        rf.set(sandbox, root);
    }

    private static void setTimeout(McpManager mcp, int seconds) throws Exception {
        java.lang.reflect.Field f = McpManager.class.getDeclaredField("toolTimeoutSeconds");
        f.setAccessible(true);
        f.setInt(mcp, seconds);
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { }
            });
        } catch (Exception ignore) { }
    }
}
