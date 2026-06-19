package com.example.imini;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-tenant usage metering, cost accounting, and quota enforcement.
 *
 * <p>Every run records input/output tokens against the calling tenant (the {@link Principal} user name) and
 * derives a cost in <em>micro-USD</em> (millionths of a dollar) from configurable per-million-token prices.
 * Costs accumulate in-memory per tenant for the current process and, when persistence is on, in the
 * {@code cost_ledger} table (one row per run) so usage survives restarts and can be reported per day.
 *
 * <p>A soft monthly token quota ({@code cost.monthly-token-quota}) is enforced BEFORE a run: a tenant over
 * quota is denied. Quota 0 disables enforcement (the default), so the open/dev experience is unchanged.
 *
 * <p>All token math is integer; cost is integer micro-USD to avoid floating-point drift in a ledger.
 */
@Component
public class CostService {

    private static final Logger log = LoggerFactory.getLogger(CostService.class);

    @Value("${cost.input-usd-per-million:0}") private double inputUsdPerMillion;
    @Value("${cost.output-usd-per-million:0}") private double outputUsdPerMillion;
    @Value("${cost.monthly-token-quota:0}") private long monthlyTokenQuota; // 0 => unlimited (default tier)
    @Value("${cost.enabled:true}") private boolean enabled;
    // Tiered quotas: cost.tiers = "free=100000,pro=5000000" (named monthly token quotas, 0=unlimited);
    // cost.tier-assignments = "alice=pro,bob=free" (which tenant is on which tier). A tenant with no
    // assignment uses cost.monthly-token-quota (the default tier).
    @Value("${cost.tiers:}") private String tiersCfg;
    @Value("${cost.tier-assignments:}") private String tierAssignmentsCfg;

    private final Database db;

    private Map<String, Long> tierQuotas = Map.of();      // tier name -> monthly token quota
    private Map<String, String> tenantTiers = Map.of();   // tenant -> tier name

    // In-memory running totals per tenant for THIS process (fast path; ledger is the durable source).
    private final Map<String, long[]> totals = new ConcurrentHashMap<>(); // tenant -> [inTok, outTok, microUsd]

    public CostService(Database db) {
        this.db = db;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        tierQuotas = parseTiers(tiersCfg);
        tenantTiers = parseAssignments(tierAssignmentsCfg);
        if (!tierQuotas.isEmpty()) {
            log.info("[cost] tiers=" + tierQuotas + "; assignments=" + tenantTiers.size()
                    + "; default quota=" + monthlyTokenQuota);
        }
    }

    public boolean enabled() { return enabled; }
    public long monthlyTokenQuota() { return monthlyTokenQuota; }

    /** Pure: parse "free=100000,pro=5000000" into a tier->quota map. Bad entries are skipped. */
    static Map<String, Long> parseTiers(String csv) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String name = p.substring(0, eq).trim();
            try {
                long q = Long.parseLong(p.substring(eq + 1).trim());
                if (!name.isEmpty() && q >= 0) out.put(name, q);
            } catch (NumberFormatException ignore) { /* skip malformed quota */ }
        }
        return out;
    }

    /** Pure: parse "alice=pro,bob=free" into a tenant->tier map. Bad entries are skipped. */
    static Map<String, String> parseAssignments(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String tenant = p.substring(0, eq).trim();
            String tier = p.substring(eq + 1).trim();
            if (!tenant.isEmpty() && !tier.isEmpty()) out.put(tenant, tier);
        }
        return out;
    }

    /**
     * Pure: resolve a tenant's effective monthly token quota from the tier maps, falling back to the
     * default. A tenant assigned to a known tier gets that tier's quota; otherwise the default quota.
     */
    static long resolveQuota(String tenant, Map<String, String> tenantTiers,
                             Map<String, Long> tierQuotas, long defaultQuota) {
        String tier = tenantTiers.get(tenant);
        if (tier != null) {
            Long q = tierQuotas.get(tier);
            if (q != null) return q;
        }
        return defaultQuota;
    }

    /** The effective monthly token quota for a tenant (0 = unlimited). */
    public long quotaFor(String tenant) {
        return resolveQuota(tenant, tenantTiers, tierQuotas, monthlyTokenQuota);
    }

    /** The tier name a tenant is on, or "default" when unassigned. */
    public String tierOf(String tenant) {
        return tenantTiers.getOrDefault(tenant, "default");
    }

    /** Pure: micro-USD for the given tokens at the configured prices. micro-USD = tokens/1e6 * usd * 1e6. */
    static long microUsd(long inTokens, long outTokens, double inUsdPerM, double outUsdPerM) {
        double usd = (inTokens / 1_000_000.0) * inUsdPerM + (outTokens / 1_000_000.0) * outUsdPerM;
        return Math.round(usd * 1_000_000.0);
    }

    /**
     * True if the tenant is allowed to start another run (i.e. not over its effective monthly token quota,
     * which is its tier's quota or the default). When the service is disabled, or the resolved quota is 0
     * (unlimited), this is always true.
     */
    public boolean allow(String tenant) {
        if (!enabled) return true;
        long quota = quotaFor(tenant);
        if (quota <= 0) return true;
        return tokensThisMonth(tenant) < quota;
    }

    /** Total tokens (input+output) charged to a tenant in the current calendar month. */
    public long tokensThisMonth(String tenant) {
        if (db.available()) {
            long monthStart = startOfMonthMs(System.currentTimeMillis());
            List<Long> rows = db.query(
                    "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM cost_ledger "
                            + "WHERE tenant=? AND ts >= ?", rs -> rs.getLong(1), tenant, monthStart);
            return rows.isEmpty() ? 0 : rows.get(0);
        }
        long[] t = totals.get(tenant);
        return t == null ? 0 : t[0] + t[1];
    }

    /**
     * Record usage for a completed run. Updates in-memory totals and (if persistent) appends a ledger row.
     * Returns the micro-USD charged for this run.
     */
    public long record(String tenant, String endpoint, String session, long inTokens, long outTokens) {
        if (!enabled) return 0;
        if (tenant == null || tenant.isBlank()) tenant = "anonymous";
        long micro = microUsd(inTokens, outTokens, inputUsdPerMillion, outputUsdPerMillion);
        totals.compute(tenant, (k, v) -> {
            if (v == null) return new long[]{inTokens, outTokens, micro};
            v[0] += inTokens; v[1] += outTokens; v[2] += micro; return v;
        });
        if (db.available()) {
            try {
                db.update("INSERT INTO cost_ledger(id, ts, tenant, endpoint, session, input_tokens, "
                                + "output_tokens, micro_usd) VALUES(?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), System.currentTimeMillis(), tenant, endpoint, session,
                        inTokens, outTokens, micro);
            } catch (Exception e) {
                log.warn("[cost] ledger write failed: " + e.getMessage());
            }
        }
        return micro;
    }

    /** Per-tenant usage summary for the current month, for GET /admin/cost. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("monthlyTokenQuota", monthlyTokenQuota);
        out.put("tiers", new LinkedHashMap<>(tierQuotas));
        out.put("inputUsdPerMillion", inputUsdPerMillion);
        out.put("outputUsdPerMillion", outputUsdPerMillion);
        List<Map<String, Object>> tenants;
        if (db.available()) {
            long monthStart = startOfMonthMs(System.currentTimeMillis());
            tenants = db.query(
                    "SELECT tenant, SUM(input_tokens), SUM(output_tokens), SUM(micro_usd), COUNT(*) "
                            + "FROM cost_ledger WHERE ts >= ? GROUP BY tenant ORDER BY SUM(micro_usd) DESC",
                    rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tenant", rs.getString(1));
                        long in = rs.getLong(2), o = rs.getLong(3);
                        m.put("inputTokens", in);
                        m.put("outputTokens", o);
                        m.put("totalTokens", in + o);
                        m.put("microUsd", rs.getLong(4));
                        m.put("usd", rs.getLong(4) / 1_000_000.0);
                        m.put("runs", rs.getLong(5));
                        return m;
                    }, monthStart);
        } else {
            List<Map<String, Object>> mem = new ArrayList<>();
            totals.forEach((tenant, t) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tenant", tenant);
                m.put("inputTokens", t[0]);
                m.put("outputTokens", t[1]);
                m.put("totalTokens", t[0] + t[1]);
                m.put("microUsd", t[2]);
                m.put("usd", t[2] / 1_000_000.0);
                mem.add(m);
            });
            tenants = mem;
        }
        out.put("tenants", tenants);
        out.put("month", "current");
        return out;
    }

    /** Pure: epoch-ms for the start (UTC) of the calendar month containing nowMs. */
    static long startOfMonthMs(long nowMs) {
        java.time.YearMonth ym = java.time.Instant.ofEpochMilli(nowMs)
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().query(java.time.YearMonth::from);
        return ym.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
