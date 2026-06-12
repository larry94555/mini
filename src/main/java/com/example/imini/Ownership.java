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
}
