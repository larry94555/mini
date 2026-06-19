package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityServiceTest {

    @Test
    void parseScopesSplitsRolesAndTools() {
        Map<String, Set<String>> s = CapabilityService.parseScopes(
                "reader=read_file|view_dir|grep, operator=*");
        assertEquals(Set.of("read_file", "view_dir", "grep"), s.get("reader"));
        assertEquals(Set.of("*"), s.get("operator"));
        assertEquals(2, s.size());
    }

    @Test
    void parseScopesIgnoresBlankAndMalformed() {
        Map<String, Set<String>> s = CapabilityService.parseScopes("  , =nothing, reader=read_file ,");
        assertEquals(1, s.size());
        assertEquals(Set.of("read_file"), s.get("reader"));
    }

    @Test
    void parseScopeStarMeansUnrestricted() {
        assertNull(CapabilityService.parseScope("*"));
        assertNull(CapabilityService.parseScope("  "));
        assertNull(CapabilityService.parseScope(null));
        assertEquals(Set.of("a", "b"), CapabilityService.parseScope("a|b"));
    }

    @Test
    void permitsHonorsWildcardAndMembership() {
        assertTrue(CapabilityService.permits((Set<String>) null, "anything")); // null scope = unrestricted
        assertTrue(CapabilityService.permits(Set.of("*"), "anything"));    // star = all
        assertTrue(CapabilityService.permits(Set.of("read_file"), "read_file"));
        assertFalse(CapabilityService.permits(Set.of("read_file"), "run_command"));
    }
}
