package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic checks for command screening and path-confinement policy. */
class SandboxTest {

    @Test
    void offModeAllowsEverything() {
        assertNull(Sandbox.screen("rm -rf /", "off", List.of(), Sandbox.DEFAULT_DENY, 2000));
    }

    @Test
    void denyOnlyBlocksDangerousCommands() {
        String result =
                Sandbox.screen("rm -rf / --no-preserve-root", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000);

        assertNotNull(result);
        assertTrue(result.contains("denied pattern"), result);
    }

    @Test
    void denyOnlyAllowsNormalCommands() {
        assertNull(Sandbox.screen("ls -la", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000));
        assertNull(Sandbox.screen("git status", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000));
    }

    @Test
    void allowlistBlocksUnlistedCommands() {
        String result = Sandbox.screen("curl http://evil", "allowlist", List.of("ls", "git status"), List.of(), 2000);

        assertNotNull(result);
        assertTrue(result.contains("allowlist"), result);
    }

    @Test
    void allowlistAllowsListedCommandsByWordOrPrefix() {
        assertNull(Sandbox.screen("ls -la", "allowlist", List.of("ls"), List.of(), 2000));
        assertNull(Sandbox.screen("git status -s", "allowlist", List.of("git status"), List.of(), 2000));
    }

    @Test
    void maxCommandLengthIsRejected() {
        String command = "echo " + "a".repeat(100);
        String result = Sandbox.screen(command, "deny-only", List.of(), List.of(), 20);

        assertNotNull(result);
        assertTrue(result.contains("max length"), result);
    }

    @Test
    void firstWordParsesExpectedPrefix() {
        assertEquals("git", Sandbox.firstWord(" git status -s "));
        assertEquals("", Sandbox.firstWord(" "));
    }

    @Test
    void readConfinementUsesWorkspaceRoot() {
        Path root = Path.of("/work/project");

        assertTrue(PermissionService.isWithin(root, "src/Main.java"));
        assertFalse(PermissionService.isWithin(root, "../../etc/passwd"));
    }
}
