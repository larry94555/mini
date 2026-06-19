package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TieredQuotaTest {

    @Test
    void parseTiersReadsNamedQuotas() {
        Map<String, Long> tiers = CostService.parseTiers("free=100000,pro=5000000,enterprise=0");
        assertEquals(3, tiers.size());
        assertEquals(100000L, tiers.get("free"));
        assertEquals(5000000L, tiers.get("pro"));
        assertEquals(0L, tiers.get("enterprise")); // 0 = unlimited
    }

    @Test
    void parseTiersSkipsMalformed() {
        Map<String, Long> tiers = CostService.parseTiers("free=100000,,bad,pro=notanumber,ok=42");
        assertEquals(2, tiers.size());
        assertEquals(100000L, tiers.get("free"));
        assertEquals(42L, tiers.get("ok"));
    }

    @Test
    void parseTiersHandlesNullAndEmpty() {
        assertTrue(CostService.parseTiers(null).isEmpty());
        assertTrue(CostService.parseTiers("").isEmpty());
    }

    @Test
    void parseAssignmentsMapsTenantsToTiers() {
        Map<String, String> a = CostService.parseAssignments("alice=pro,bob=free");
        assertEquals("pro", a.get("alice"));
        assertEquals("free", a.get("bob"));
    }

    @Test
    void resolveQuotaUsesTierThenDefault() {
        Map<String, String> assignments = Map.of("alice", "pro", "bob", "free");
        Map<String, Long> tiers = Map.of("pro", 5_000_000L, "free", 100_000L);
        long defaultQuota = 1_000_000L;
        // assigned tenants get their tier's quota
        assertEquals(5_000_000L, CostService.resolveQuota("alice", assignments, tiers, defaultQuota));
        assertEquals(100_000L, CostService.resolveQuota("bob", assignments, tiers, defaultQuota));
        // unassigned tenant falls back to the default
        assertEquals(defaultQuota, CostService.resolveQuota("carol", assignments, tiers, defaultQuota));
        // assigned to an unknown tier also falls back to the default
        Map<String, String> badAssign = Map.of("dave", "ghosttier");
        assertEquals(defaultQuota, CostService.resolveQuota("dave", badAssign, tiers, defaultQuota));
    }
}
