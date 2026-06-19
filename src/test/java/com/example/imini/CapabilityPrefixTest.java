package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prefix/wildcard matching that lets a scope cover a whole MCP server (e.g. github_*). */
class CapabilityPrefixTest {

    @Test
    void prefixTokenMatchesMcpServerTools() {
        Set<String> scope = Set.of("read_file", "github_*");
        assertTrue(CapabilityService.permits(scope, "github_search"));
        assertTrue(CapabilityService.permits(scope, "github_create_issue"));
        assertTrue(CapabilityService.permits(scope, "read_file"));
        assertFalse(CapabilityService.permits(scope, "gitlab_search"));
        assertFalse(CapabilityService.permits(scope, "run_command"));
    }

    @Test
    void bareStarStillAllowsEverything() {
        assertTrue(CapabilityService.permits(Set.of("*"), "anything_at_all"));
        assertTrue(CapabilityService.permits((Set<String>) null, "anything_at_all"));
    }

    @Test
    void prefixDoesNotMatchShorterName() {
        // "github_*" should not match the literal "github" (needs the prefix up to the star)
        assertFalse(CapabilityService.permits(Set.of("github_*"), "github"));
        assertTrue(CapabilityService.permits(Set.of("github_*"), "github_"));
    }
}
