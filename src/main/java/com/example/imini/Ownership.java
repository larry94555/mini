package com.example.imini;

/**
 * Per-resource access policy. Admins see/act on everything; an UNOWNED resource (owner == null, e.g.
 * a legacy session created before ownership or a brand-new one) is open so nobody is locked out; an
 * owned resource is reachable only by its owner. Pure + static so it is unit-testable.
 */
public final class Ownership {

    private Ownership() {}

    public static boolean canAccess(Principal caller, String owner) {
        if (caller != null && caller.isAdmin()) return true;
        if (owner == null) return true;
        return caller != null && owner.equals(caller.user());
    }

    /**
     * READ access: anyone who {@link #canAccess} (admin/owner/unowned) plus users the session has been
     * explicitly shared with. Used for read-only endpoints; management/mutation still require
     * {@link #canAccess}.
     */
    public static boolean canRead(Principal caller, String owner, java.util.Set<String> readers) {
        if (canAccess(caller, owner)) return true;
        return caller != null && readers != null && readers.contains(caller.user());
    }
}
