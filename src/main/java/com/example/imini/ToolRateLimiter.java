package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
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
 * <p><b>Persistence.</b> When SQLite persistence is available and {@code tool-rate-limit.persistent=true}
 * (the default), window state is stored in the shared {@code rate_limits} table under {@code tool:}-prefixed
 * keys — so limits survive a restart and are shared across instances pointing at the same database, exactly
 * like the HTTP limiter. With persistence off (or no database) it falls back to in-memory state that resets
 * on restart.
 */
@Component
public class ToolRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ToolRateLimiter.class);

    /** Key namespace inside the shared rate_limits table, to avoid clashing with HTTP limiter keys. */
    static final String KEY_PREFIX = "tool:";

    @Value("${tool-rate-limit.enabled:false}") private boolean enabled;
    @Value("${tool-rate-limit.limits:}") private String limitsCfg;
    @Value("${tool-rate-limit.persistent:true}") private boolean persistent;

    private final Database db;

    /** tool -> [limit, windowMs]. Immutable after init. */
    private Map<String, long[]> limits = Map.of();

    /** "tenant:tool" -> sliding-window state [windowStart, current, prev]. In-memory fallback. */
    private final Map<String, long[]> state = new ConcurrentHashMap<>();

    public ToolRateLimiter(Database db) {
        this.db = db;
    }

    public boolean enabled() { return enabled; }

    @jakarta.annotation.PostConstruct
    public void init() {
        this.limits = parseLimits(limitsCfg);
        if (enabled && !limits.isEmpty()) {
            log.info("[tool-rate-limit] enabled for " + limits.size() + " tool(s): " + limits.keySet()
                    + (persistentActive() ? " (persistent)" : " (in-memory)"));
        }
    }

    private boolean persistentActive() {
        return persistent && db != null && db.available();
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
        return persistentActive() ? allowPersistent(key, limit, windowMs, nowMs)
                                  : allowInMemory(key, limit, windowMs, nowMs);
    }

    private boolean allowInMemory(String key, long limit, long windowMs, long nowMs) {
        long[] s = state.computeIfAbsent(key, k -> new long[]{nowMs, 0, 0});
        long[] r = RateLimiter.slidingStep(s[0], s[1], s[2], nowMs, windowMs);
        if (r[3] > limit) return false; // would exceed; do NOT commit
        s[0] = r[0]; s[1] = r[1]; s[2] = r[2];
        return true;
    }

    private boolean allowPersistent(String key, long limit, long windowMs, long nowMs) {
        String rlKey = KEY_PREFIX + key;
        long windowStart = nowMs, current = 0, prev = 0;
        try {
            List<long[]> rows = db.query(
                    "SELECT window_start, count, prev_count FROM rate_limits WHERE rl_key=?",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, rlKey);
            if (!rows.isEmpty()) { windowStart = rows.get(0)[0]; current = rows.get(0)[1]; prev = rows.get(0)[2]; }
            long[] r = RateLimiter.slidingStep(windowStart, current, prev, nowMs, windowMs);
            if (r[3] > limit) return false; // over the limit; don't persist the increment
            db.update("INSERT INTO rate_limits(rl_key, window_start, count, prev_count) VALUES(?,?,?,?) "
                            + "ON CONFLICT(rl_key) DO UPDATE SET window_start=excluded.window_start, "
                            + "count=excluded.count, prev_count=excluded.prev_count",
                    rlKey, r[0], r[1], r[2]);
            return true;
        } catch (Exception ex) {
            // On any DB error, fail open (allow) rather than break the run; fall back to in-memory.
            log.warn("[tool-rate-limit] persistence error for " + rlKey + ", falling back: " + ex.getMessage());
            return allowInMemory(key, limit, windowMs, nowMs);
        }
    }

    /** Describe the configured limits for the admin endpoint. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("persistent", persistentActive());
        Map<String, String> ls = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : limits.entrySet()) {
            ls.put(e.getKey(), e.getValue()[0] + "/" + (e.getValue()[1] / 1000) + "s");
        }
        m.put("limits", ls);
        return m;
    }
}
