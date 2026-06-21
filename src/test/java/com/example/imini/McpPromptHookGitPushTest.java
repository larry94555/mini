package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MCP prompt slash-command parsing, hook session-start/notification config, and git_push argv + gate. */
class McpPromptHookGitPushTest {

    // ---- Feature 1: MCP prompt slash-command parsing (pure) ----

    @Test
    void commandTokenParsesLeadingSlash() {
        assertEquals("mcp__srv__review", McpManager.commandToken("/mcp__srv__review file=A.java"));
        assertEquals("mcp__srv__review", McpManager.commandToken("  /mcp__srv__review  "));
        assertNull(McpManager.commandToken("no slash here"));
        assertNull(McpManager.commandToken(null));
    }

    @Test
    void argStringIsEverythingAfterTheCommand() {
        assertEquals("file=A.java scope=auth", McpManager.argString("/mcp__srv__review file=A.java scope=auth"));
        assertEquals("", McpManager.argString("/mcp__srv__review"));
        assertEquals("", McpManager.argString(null));
    }

    @Test
    void promptArgsParseKeyValues() {
        Map<String, Object> a = McpManager.parsePromptArgs("file=A.java scope=auth");
        assertEquals("A.java", a.get("file"));
        assertEquals("auth", a.get("scope"));
        assertTrue(McpManager.parsePromptArgs("").isEmpty());
        assertTrue(McpManager.parsePromptArgs(null).isEmpty());
        assertTrue(McpManager.parsePromptArgs("noequalshere").isEmpty()); // tokens without '=' are ignored
    }

    @Test
    void noPromptsMeansNotACommand() {
        McpManager m = new McpManager(); // no mcp.json loaded
        assertFalse(m.isPromptCommand("/mcp__srv__review"));
        assertEquals("", m.promptCommandHelp());
        assertNull(m.renderPromptCommand("/mcp__srv__review"));
    }

    // ---- Feature 2: hook session-start / notification config ----

    @Test
    void hookFlagsFalseWhenUnconfigured() {
        HookService h = new HookService(); // no hooks.json
        assertFalse(h.hasSessionStartHooks());
        assertFalse(h.hasNotificationHooks());
        assertEquals("", h.runSessionStart("sess-1"));   // no hooks -> no injected context
        h.runNotification("approval requested", "git_commit"); // no-op, must not throw
    }

    // ---- Feature 3: git push argv + remote validation ----

    @Test
    void pushArgsOmitBlanks() {
        assertEquals(List.of("push"), GitWriteTools.pushArgs("", "", false));
        assertEquals(List.of("push", "-u"), GitWriteTools.pushArgs("", "", true));
        assertEquals(List.of("push", "origin"), GitWriteTools.pushArgs("origin", "", false));
        assertEquals(List.of("push", "origin", "main"), GitWriteTools.pushArgs("origin", "main", false));
        assertEquals(List.of("push", "-u", "origin", "feature/x"), GitWriteTools.pushArgs("origin", "feature/x", true));
    }

    @Test
    void remoteNameValidation() {
        assertTrue(GitWriteTools.isValidRemoteName("origin"));
        assertFalse(GitWriteTools.isValidRemoteName("-f"));
        assertFalse(GitWriteTools.isValidRemoteName("a b"));
        assertFalse(GitWriteTools.isValidRemoteName(""));
    }

    @Test
    void gitPushDisabledByDefault() {
        GitWriteTools g = new GitWriteTools(new Sandbox()); // allowPush defaults false (field not injected)
        Tool push = null;
        for (Tool t : g.all()) if ("git_push".equals(t.name)) push = t;
        assertTrue(push != null && push.mutating);
        assertEquals(4, g.all().size());
        String r = push.executor.apply(Map.of());
        assertTrue(r.startsWith("ERROR") && r.contains("disabled"));
    }
}
