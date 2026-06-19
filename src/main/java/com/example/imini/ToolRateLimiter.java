package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tool, per-tenant rate limiting: caps how often an expensive tool (e.g. {@code web_fetch},
 * {@code run_command}, an MCP server tool) may run for a given tenant within a sliding time window. This
 * complements capability scoping ("<i>may</i> you use this tool?") with a throughput limit ("<i>how often?</i>"),
 * building on the same per-tenant identity the cost service uses and the sliding-window math the HTTP
 * {@link RateLimiter} uses.
 *
 * <p>Configure with {@code tool-rate-limit.enabled=true} and {@code tool-rate-limit.limits}, a comma-separated
 * list of {@code tool=limit/windowSeconds} entries, e.g. {@code web_fetch=10/60, run_command=5/60}. A tool
 * with no entry is unlimited. Limits are keyed by {@code tenant + ":" + tool}, so one noisy tenant can't
 * exhaust another's budget. Off by default, so existing behaviour is unchanged.
 *
 * <p>State is in-memory (per process); unlike the HTTP limiter there is no SQLite persistence, so limits
 * reset on restart. That is an acceptable trade-off for a throttle whose windows are seconds-to-minutes.
 */
@Component
public class ToolRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ToolRateLimiter.class);

    @Value("${tool-rate-limit.enabled:false}") private boolean enabled;
    @Value("${tool-rate-limit.limits:}") private String limitsCfg;

    /** tool -> [limit, windowMs]. Immutable after init. */
    private Map<String, long[]> limits = Map.of();

    /** "tenant:tool" -> sliding-window state [windowStart, current, prev]. */
    private final Map<String, long[]> state = new ConcurrentHashMap<>();

    public boolean enabled() { return enabled; }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.limits = parseLimits(limitsCfg);
        if (enabled && !limits.isEmpty()) {
            log.info("[tool-rate-limit] enabled for " + limits.size() + " tool(s): " + limits.keySet());
        }
    }

    /**
     * Pure: parse {@code tool=limit/windowSeconds} entries (comma-separated) into a map of
     * {@code tool -> [limit, windowMs]}. Malformed or non-positive entries are skipped.
     */
    static Map<String, long[]> parseLimits(String csv) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) return out;
        for (String entry : csv.split(",")) {
            String e = entry.trim();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) continue;
            String tool = e.substring(0, eq).trim();
            String spec = e.substring(eq + 1).trim();
            int slash = spec.indexOf('/');
            if (slash <= 0 || slash == spec.length() - 1) continue;
            try {
                long limit = Long.parseLong(spec.substring(0, slash).trim());
                long windowSec = Long.parseLong(spec.substring(slash + 1).trim());
                if (limit > 0 && windowSec > 0 && !tool.isEmpty()) {
                    out.put(tool, new long[]{limit, windowSec * 1000L});
                }
            } catch (NumberFormatException ignore) {
                // skip malformed numeric spec
            }
        }
        return out;
    }

    /** Is {@code tool} allowed to run for {@code tenant} right now? Records the call when allowed. */
    public boolean allow(String tenant, String tool) {
        return allow(tenant, tool, System.currentTimeMillis());
    }

    synchronized boolean allow(String tenant, String tool, long nowMs) {
        if (!enabled) return true;
        long[] cfg = limits.get(tool);
        if (cfg == null) return true; // no limit configured for this tool
        long limit = cfg[0], windowMs = cfg[1];
        String key = (tenant == null ? "anonymous" : tenant) + ":" + tool;
        long[] s = state.computeIfAbsent(key, k -> new long[]{nowMs, 0, 0});
        long[] r = RateLimiter.slidingStep(s[0], s[1], s[2], nowMs, windowMs);
        // r = [windowStart, newCurrent, prev, weighted]; weighted is scaled estimate of the trailing rate.
        if (r[3] > limit) {
            return false; // would exceed the limit; do NOT commit the increment
        }
        s[0] = r[0]; s[1] = r[1]; s[2] = r[2];
        return true;
    }

    /** Describe the configured limits for the admin endpoint. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        Map<String, String> ls = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : limits.entrySet()) {
            ls.put(e.getKey(), e.getValue()[0] + "/" + (e.getValue()[1] / 1000) + "s");
        }
        m.put("limits", ls);
        return m;
    }
}
