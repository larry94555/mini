package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dead-letter retention cutoff, dedup window logic, and per-route counter shape. */
class AlertRetentionDedupTest {

    @Test
    void cutoffComputesRetentionBoundary() {
        long now = 1_000_000_000_000L;
        assertEquals(now - 24L * 3_600_000L, AlertSink.cutoff(now, 24));
        assertEquals(now - 168L * 3_600_000L, AlertSink.cutoff(now, 168));
    }

    @Test
    void cutoffZeroOrNegativeMeansKeepForever() {
        assertEquals(0L, AlertSink.cutoff(1_000_000L, 0));
        assertEquals(0L, AlertSink.cutoff(1_000_000L, -5));
    }

    @Test
    void purgeNoOpWithoutDb() {
        AlertSink s = new AlertSink(null, null);
        assertEquals(0, s.purgeOlderThan(168, System.currentTimeMillis()));
    }

    @Test
    void dedupDisabledAlwaysForwards() throws Exception {
        AlertSink s = new AlertSink(null, null); // dedupWindowSeconds defaults to 0
        for (int i = 0; i < 5; i++) {
            assertTrue(s.dedupDecide("capability_denied|tool:x", 1000L + i).forward());
        }
    }

    @Test
    void dedupCollapsesWithinWindowAndReopensAfter() throws Exception {
        AlertSink s = new AlertSink(null, null);
        setField(s, "dedupWindowSeconds", 10L); // 10s window
        String key = "capability_denied|tool:x";
        long t0 = 1_000_000L;
        AlertSink.DedupResult first = s.dedupDecide(key, t0);
        assertTrue(first.forward());                 // opens window
        assertFalse(s.dedupDecide(key, t0 + 1000).forward());  // within window -> suppressed
        assertFalse(s.dedupDecide(key, t0 + 5000).forward());  // still suppressed
        AlertSink.DedupResult reopen = s.dedupDecide(key, t0 + 11_000); // window elapsed
        assertTrue(reopen.forward());
        assertEquals(2, reopen.suppressedSincePrev()); // reports the 2 it collapsed
    }

    @Test
    void dedupIndependentPerKey() throws Exception {
        AlertSink s = new AlertSink(null, null);
        setField(s, "dedupWindowSeconds", 10L);
        assertTrue(s.dedupDecide("a|x", 1000L).forward());
        assertTrue(s.dedupDecide("b|y", 1000L).forward()); // different key, own window
    }

    @Test
    void statsExposesByRouteAndSuppressed() {
        AlertSink s = new AlertSink(null, null);
        Map<String, Object> st = s.stats();
        assertTrue(st.containsKey("by_route"));
        assertTrue(st.containsKey("suppressed"));
        assertTrue(st.containsKey("replayed"));
    }

    private static void setField(Object o, String f, Object v) throws Exception {
        var fl = AlertSink.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }
}
