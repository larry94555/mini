package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-resource access: admins see all, owners see their own, others are denied, unowned is open. */
class OwnershipTest {

    private final Principal admin = new Principal("alice", "admin");
    private final Principal bob = new Principal("bob", "member");
    private final Principal cara = new Principal("cara", "member");

    @Test
    void adminAccessesEverything() {
        assertTrue(Ownership.canAccess(admin, "bob"));
        assertTrue(Ownership.canAccess(admin, "cara"));
        assertTrue(Ownership.canAccess(admin, null));
    }

    @Test
    void ownerAccessesOwnResource() {
        assertTrue(Ownership.canAccess(bob, "bob"));
    }

    @Test
    void otherMemberIsDenied() {
        assertFalse(Ownership.canAccess(cara, "bob"));
        assertFalse(Ownership.canAccess(bob, "cara"));
    }

    @Test
    void unownedResourceIsOpen() {
        assertTrue(Ownership.canAccess(bob, null), "legacy/new (unowned) sessions are not locked");
    }

    @Test
    void anonymousIsAdminSoAuthDisabledSeesAll() {
        assertTrue(Ownership.canAccess(Principal.ANON, "bob"));
    }

    @Test
    void sharedReaderCanReadButOthersCannot() {
        java.util.Set<String> readers = java.util.Set.of("cara");
        assertTrue(Ownership.canRead(cara, "bob", readers), "cara was granted read access");
        assertFalse(Ownership.canRead(cara, "bob", java.util.Set.of()), "no share -> denied");
        assertTrue(Ownership.canRead(bob, "bob", java.util.Set.of()), "owner always reads");
        assertTrue(Ownership.canRead(admin, "bob", java.util.Set.of()), "admin always reads");
    }

    @Test
    void canReadToleratesNullReaders() {
        assertFalse(Ownership.canRead(cara, "bob", null));
        assertTrue(Ownership.canRead(cara, null, null), "unowned stays open");
    }
}
