package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A stateless, signed-token CSRF guard for the admin HTML viewer's state-changing actions (replay / ack /
 * delete / bulk / escalate). Each token is {@code base64url(expiryEpochMs) + "." + base64url(HMAC-SHA256(secret,
 * expiryEpochMs))}: the server mints one (with a TTL) into the rendered viewer, and validates a presented token
 * by recomputing the HMAC and checking it hasn't expired — no server-side token storage.
 *
 * <p>Because validation only needs the shared secret, tokens verify across instances when
 * {@code alerts.csrf-secret} is configured (set the same value on every node); left blank, a random per-process
 * secret is generated (single-instance only). Tokens rotate naturally via the TTL ({@code alerts.csrf-ttl-seconds},
 * default 1h) — the viewer always embeds a fresh one. Enforcement is toggleable with {@code alerts.admin-csrf}.
 */
@Component
public class CsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(CsrfGuard.class);

    @Value("${alerts.admin-csrf:true}") private boolean enabled;
    @Value("${alerts.csrf-secret:}") private String configuredSecret;
    @Value("${alerts.csrf-ttl-seconds:3600}") private long ttlSeconds;

    private volatile byte[] secret;

    public CsrfGuard() {}

    /** The signing secret: the configured shared value (UTF-8) if set, else a random per-process key. */
    private byte[] secret() {
        byte[] s = secret;
        if (s == null) {
            synchronized (this) {
                if (secret == null) {
                    if (configuredSecret != null && !configuredSecret.isBlank()) {
                        secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
                    } else {
                        byte[] r = new byte[32];
                        new SecureRandom().nextBytes(r);
                        secret = r;
                        log.info("[alerts] CSRF using a random per-process secret; set alerts.csrf-secret to "
                                + "share tokens across instances");
                    }
                }
                s = secret;
            }
        }
        return s;
    }

    public boolean enabled() { return enabled; }

    /** Mint a fresh token valid for the configured TTL from now. */
    public String token() { return mint(System.currentTimeMillis(), secret(), Math.max(1, ttlSeconds)); }

    /** Pure: build a signed token for an expiry of {@code nowMs + ttlSeconds*1000} using {@code key}. */
    static String mint(long nowMs, byte[] key, long ttlSeconds) {
        long exp = nowMs + ttlSeconds * 1000L;
        String expPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(exp).getBytes(StandardCharsets.UTF_8));
        return expPart + "." + sign(key, expPart);
    }

    /** Pure: base64url(HMAC-SHA256(key, msg)). */
    static String sign(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC unavailable", ex);
        }
    }

    /** Pure: verify a presented token against {@code key} at {@code nowMs} (signature valid and not expired). */
    static boolean verify(String token, byte[] key, long nowMs) {
        if (token == null) return false;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return false;
        String expPart = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sig, sign(key, expPart))) return false;
        try {
            long exp = Long.parseLong(new String(Base64.getUrlDecoder().decode(expPart), StandardCharsets.UTF_8));
            return nowMs < exp;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Pure: constant-time string comparison (avoids leaking match length via timing). */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    /** True when the presented token is acceptable (guard disabled, or a valid unexpired signature). */
    public boolean valid(String presented) {
        return !enabled || verify(presented, secret(), System.currentTimeMillis());
    }

    /** Enforce: throw 403 when enforcement is on and the presented token is missing/invalid/expired. */
    public void require(String presented) {
        if (!valid(presented)) {
            log.warn("[alerts] rejected state-changing admin action: missing/invalid/expired CSRF token");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing or invalid CSRF token");
        }
    }
}
