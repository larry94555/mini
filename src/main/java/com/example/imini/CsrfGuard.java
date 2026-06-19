package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * A small synchronizer-token guard for the admin HTML viewer's state-changing actions (replay / ack / delete /
 * bulk / escalate). The server holds one random token per process; the rendered viewer embeds it and sends it
 * back on every mutating {@code fetch} (header {@code X-CSRF-Token}, or a {@code csrf} query param fallback).
 * Requests whose token doesn't match are rejected with 403, so a cross-site page cannot drive these endpoints
 * even if the browser would otherwise attach the admin credential.
 *
 * <p>Defense in depth: the admin API is already header-authenticated (which browsers won't replay cross-origin
 * for custom headers), but the interactive viewer makes destructive bulk actions one click away, so an
 * explicit token is warranted. Enforcement can be turned off with {@code alerts.admin-csrf=false} for purely
 * scripted/API deployments. The token is available to scripts at {@code GET /admin/alerts/csrf}.
 */
@Component
public class CsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(CsrfGuard.class);

    @Value("${alerts.admin-csrf:true}") private boolean enabled;

    private final String token;

    public CsrfGuard() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public boolean enabled() { return enabled; }

    /** The current per-process token, embedded in the viewer and served at /admin/alerts/csrf. */
    public String token() { return token; }

    /** Pure: constant-time string comparison (avoids leaking match length via timing). */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] y = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    /** True when the presented token is acceptable (guard disabled, or it matches). */
    public boolean valid(String presented) {
        return !enabled || constantTimeEquals(presented, token);
    }

    /** Enforce: throw 403 when enforcement is on and the presented token doesn't match. */
    public void require(String presented) {
        if (!valid(presented)) {
            log.warn("[alerts] rejected state-changing admin action: missing/invalid CSRF token");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing or invalid CSRF token");
        }
    }
}
