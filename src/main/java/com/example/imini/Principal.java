package com.example.imini;

/**
 * The authenticated caller: a user name and a role. Roles are simple strings; only "admin" grants
 * access to admin-gated endpoints (everything else is a regular "member"). When auth is disabled the
 * request runs as {@link #ANON}, which is treated as admin so the open/dev experience is unchanged.
 */
public record Principal(String user, String role) {

    public static final Principal ANON = new Principal("anonymous", "admin");

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }
}
