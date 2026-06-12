package com.example.imini;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, unit-testable role-based access policy. Identity comes from the API key (see AuthFilter):
 * legacy {@code auth.keys} entries are treated as admins (backward compatible), while
 * {@code auth.principals} ("user:key:role") assigns explicit roles. A small set of {@code
 * auth.admin-paths} requires the admin role; everything else is open to any authenticated member.
 */
public final class Rbac {

    private Rbac() {}

    /** Parse "user:key:role, user:key:role" into key -> Principal. Malformed entries are skipped. */
    public static Map<String, Principal> parsePrincipals(String csv) {
        Map<String, Principal> m = new LinkedHashMap<>();
        if (csv == null) return m;
        for (String item : csv.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            String[] parts = s.split(":");
            if (parts.length != 3) continue;
            String user = parts[0].trim();
            String key = parts[1].trim();
            String role = parts[2].trim().toLowerCase(Locale.ROOT);
            if (user.isEmpty() || key.isEmpty()) continue;
            m.put(key, new Principal(user, role.isEmpty() ? "member" : role));
        }
        return m;
    }

    public static Set<String> parseAdminPaths(String csv) {
        Set<String> s = new LinkedHashSet<>();
        if (csv == null) return s;
        for (String p : csv.split(",")) if (!p.isBlank()) s.add(p.trim());
        return s;
    }

    /** True if the path is admin-gated (exact match, or a subpath of a configured admin path). */
    public static boolean isAdminPath(String path, Set<String> adminPaths) {
        if (path == null) return false;
        for (String a : adminPaths) {
            String base = a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
            if (path.equals(base) || path.startsWith(base + "/")) return true;
        }
        return false;
    }

    /** Whether the principal may access the path: non-admin paths are open to members; admin paths need admin. */
    public static boolean allows(Principal p, String path, Set<String> adminPaths) {
        return !isAdminPath(path, adminPaths) || (p != null && p.isAdmin());
    }
}
