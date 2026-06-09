package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for the sandbox command-screening and path-confinement policy. */
class SandboxTest {

    @Test
    void offModeAllowsEverything() {
        assertNull(Sandbox.screen("rm -rf /", "off", List.of(), Sandbox.DEFAULT_DENY, 2000));
    }

    @Test
    void denyOnlyBlocksDangerous() {
        String r = Sandbox.screen("rm -rf / --no-preserve-root", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000);
        assertNotNull(r);
        assertTrue(r.contains("denied pattern"), r);
    }

    @Test
    void denyOnlyAllowsNormalCommands() {
        assertNull(Sandbox.screen("ls -la", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000));
        assertNull(Sandbox.screen("git status", "deny-only", List.of(), Sandbox.DEFAULT_DENY, 2000));
    }

    @Test
    void allowlistBlocksUnlisted() {
        String r = Sandbox.screen("curl http://evil", "allowlist", List.of("ls", "git status"), List.of(), 2000);
        assertNotNull(r);
        assertTrue(r.contains("allowlist"), r);
    }

    @Test
    void allowlistAllowsListedByWordOrPrefix() {
        assertNull(Sandbox.screen("ls -la", "allowlist", List.of("ls"), List.of(), 2000));
        assertNull(Sandbox.screen("git status -s", "allowlist", List.of("git status"), List.of(), 2000));
    }

    @Test
    void maxLengthRejected() {
        String big = "echo " + "a".repeat(100);
        String r = Sandbox.screen(big, "deny-only", List.of(), List.of(), 20);
        assertNotNull(r);
        assertTrue(r.contains("max length"), r);
    }

    @Test
    void firstWordParses() {
        assertEquals("git", Sandbox.firstWord("  git status -s "));
        assertEquals("", Sandbox.firstWord("   "));
    }

    @Test
    void readConfinementUsesWorkspaceRoot() {
        Path root = Path.of("/work/project");
        assertTrue(PermissionService.isWithin(root, "src/Main.java"));
        assertFalse(PermissionService.isWithin(root, "../../etc/passwd"));
    }
}
