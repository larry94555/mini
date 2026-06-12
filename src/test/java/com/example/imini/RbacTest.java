package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Role policy: who may reach admin-gated paths, and how principals/paths are parsed. */
class RbacTest {

    @Test
    void parsePrincipalsReadsUserKeyRoleAndSkipsMalformed() {
        Map<String, Principal> p = Rbac.parsePrincipals("alice:secretA:admin, bob:secretB:member, oops:only-two");
        assertEquals(2, p.size());
        assertEquals("alice", p.get("secretA").user());
        assertTrue(p.get("secretA").isAdmin());
        assertEquals("member", p.get("secretB").role());
        assertFalse(p.get("secretB").isAdmin());
    }

    @Test
    void adminPathMatchesExactAndSubpath() {
        Set<String> admin = Rbac.parseAdminPaths("/metrics,/approve,/approvals");
        assertTrue(Rbac.isAdminPath("/metrics", admin));
        assertTrue(Rbac.isAdminPath("/approvals", admin));
        assertTrue(Rbac.isAdminPath("/approve/extra", admin)); // subpath
        assertFalse(Rbac.isAdminPath("/ask", admin));
        assertFalse(Rbac.isAdminPath("/metricsX", admin));     // not a subpath of /metrics
    }

    @Test
    void membersBlockedFromAdminPathsButFreeElsewhere() {
        Set<String> admin = Rbac.parseAdminPaths("/metrics,/approve");
        Principal alice = new Principal("alice", "admin");
        Principal bob = new Principal("bob", "member");
        assertTrue(Rbac.allows(alice, "/metrics", admin));
        assertFalse(Rbac.allows(bob, "/metrics", admin));
        assertTrue(Rbac.allows(bob, "/ask", admin));
        assertTrue(Rbac.allows(bob, "/chat", admin));
    }

    @Test
    void anonymousIsAdminSoAuthDisabledStaysOpen() {
        Set<String> admin = Rbac.parseAdminPaths("/metrics");
        assertTrue(Principal.ANON.isAdmin());
        assertTrue(Rbac.allows(Principal.ANON, "/metrics", admin));
    }
}
