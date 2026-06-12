package com.example.imini;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * API-key authentication + per-key rate limiting, plus request accounting for /metrics. A @Component
 * Filter is auto-registered by Spring Boot for all paths.
 *
 *   auth.enabled=false (default)  -> open, but still counts requests (backward compatible).
 *   auth.enabled=true             -> requires a valid key in the auth header (or "Authorization:
 *                                    Bearer <key>"); unknown key -> 401, over the rate limit -> 429.
 *
 * auth.keys is a comma-separated list of "key" or "label:key" (label is used in metrics attribution).
 * auth.open-paths (default /health) are always allowed through.
 */
@Component
public class AuthFilter implements Filter {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthFilter.class);


    @Value("${auth.enabled:false}") private boolean enabled;
    @Value("${auth.header:X-API-Key}") private String header;
    @Value("${auth.keys:}") private String keysCfg;
    @Value("${auth.open-paths:/health}") private String openPathsCfg;
    @Value("${auth.rate-limit-per-minute:0}") private int rateLimitPerMinute;
    @Value("${auth.principals:}") private String principalsCfg;
    @Value("${auth.admin-paths:/metrics,/approve,/approvals}") private String adminPathsCfg;

    private final Metrics metrics;
    private Map<String, Principal> keyToPrincipal = Map.of();
    private Set<String> openPaths = Set.of();
    private Set<String> adminPaths = Set.of();
    private RateLimiter limiter;

    public AuthFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        Map<String, Principal> merged = new LinkedHashMap<>();
        // legacy auth.keys -> admins (backward compatible: before RBAC, any valid key had full access)
        for (Map.Entry<String, String> e : parseKeys(keysCfg).entrySet()) {
            merged.put(e.getKey(), new Principal(e.getValue(), "admin"));
        }
        // auth.principals ("user:key:role") assign explicit roles (override a legacy key if reused)
        merged.putAll(Rbac.parsePrincipals(principalsCfg));
        keyToPrincipal = merged;
        adminPaths = Rbac.parseAdminPaths(adminPathsCfg);
        openPaths = new LinkedHashSet<>();
        for (String p : openPathsCfg.split(",")) if (!p.isBlank()) openPaths.add(p.trim());
        limiter = new RateLimiter(rateLimitPerMinute);
        long admins = keyToPrincipal.values().stream().filter(Principal::isAdmin).count();
        log.info("[auth] enabled=" + enabled + "; principals=" + keyToPrincipal.size()
                + " (admins=" + admins + "); admin-paths=" + adminPaths
                + "; rate-limit/min=" + rateLimitPerMinute + "; open=" + openPaths);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();

        metrics.inc("requests");

        if (!enabled || openPaths.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String key = extractKey(req.getHeader(header), req.getHeader("Authorization"));
        Principal principal = matchPrincipal(key);
        if (principal == null) {
            metrics.inc("auth_rejected");
            deny(res, 401, "missing or invalid API key");
            return;
        }
        if (!limiter.allow(principal.user(), System.currentTimeMillis())) {
            metrics.inc("rate_limited");
            deny(res, 429, "rate limit exceeded");
            return;
        }
        if (!Rbac.allows(principal, path, adminPaths)) {
            metrics.inc("auth_forbidden");
            deny(res, 403, "'" + principal.role() + "' role may not access " + path + " (admin only)");
            return;
        }
        metrics.incKey(principal.user());
        RequestContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** Constant-time match of the presented key against configured keys; returns the Principal or null. */
    private Principal matchPrincipal(String key) {
        if (key == null) return null;
        for (Map.Entry<String, Principal> e : keyToPrincipal.entrySet()) {
            if (constantTimeEquals(e.getKey(), key)) return e.getValue();
        }
        return null;
    }

    private void deny(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }

    // --- pure, unit-testable helpers ----------------------------------------

    public static Map<String, String> parseKeys(String csv) {
        Map<String, String> m = new LinkedHashMap<>();
        if (csv == null) return m;
        for (String item : csv.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            int c = s.indexOf(':');
            if (c > 0) {
                m.put(s.substring(c + 1).trim(), s.substring(0, c).trim());
            } else {
                m.put(s, s.length() > 6 ? s.substring(0, 6) + "..." : s);
            }
        }
        return m;
    }

    public static String extractKey(String headerVal, String authorizationVal) {
        if (headerVal != null && !headerVal.isBlank()) return headerVal.trim();
        if (authorizationVal != null && authorizationVal.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorizationVal.substring(7).trim();
        }
        return null;
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
