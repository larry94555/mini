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

    @Value("${auth.enabled:false}") private boolean enabled;
    @Value("${auth.header:X-API-Key}") private String header;
    @Value("${auth.keys:}") private String keysCfg;
    @Value("${auth.open-paths:/health}") private String openPathsCfg;
    @Value("${auth.rate-limit-per-minute:0}") private int rateLimitPerMinute;

    private final Metrics metrics;
    private Map<String, String> keyToLabel = Map.of();
    private Set<String> openPaths = Set.of();
    private RateLimiter limiter;

    public AuthFilter(Metrics metrics) {
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        keyToLabel = parseKeys(keysCfg);
        openPaths = new LinkedHashSet<>();
        for (String p : openPathsCfg.split(",")) if (!p.isBlank()) openPaths.add(p.trim());
        limiter = new RateLimiter(rateLimitPerMinute);
        System.out.println("[auth] enabled=" + enabled + "; keys=" + keyToLabel.size()
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
        String label = matchKey(key);
        if (label == null) {
            metrics.inc("auth_rejected");
            deny(res, 401, "missing or invalid API key");
            return;
        }
        if (!limiter.allow(label, System.currentTimeMillis())) {
            metrics.inc("rate_limited");
            deny(res, 429, "rate limit exceeded");
            return;
        }
        metrics.incKey(label);
        chain.doFilter(request, response);
    }

    /** Constant-time match of the presented key against configured keys; returns the label or null. */
    private String matchKey(String key) {
        if (key == null) return null;
        for (Map.Entry<String, String> e : keyToLabel.entrySet()) {
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
