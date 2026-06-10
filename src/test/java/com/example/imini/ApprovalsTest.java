package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic checks for the pending-approval registry (no Spring / model). */
class ApprovalsTest {

    @Test
    void resolveDeliversDecisionToWaiter() throws Exception {
        Approvals approvals = new Approvals();
        AtomicReference<String> result = new AtomicReference<>();
        Thread waiter = new Thread(() ->
                result.set(approvals.await("s1", "write_file", "{\"path\":\"a\"}", 5000, "deny")));
        waiter.start();

        String id = null;
        for (int i = 0; i < 100 && id == null; i++) {
            List<Map<String, Object>> pend = approvals.list("s1");
            if (!pend.isEmpty()) id = String.valueOf(pend.get(0).get("id"));
            else Thread.sleep(10);
        }
        assertNotNull(id, "approval should be pending");
        assertTrue(approvals.resolve(id, "allow"));
        waiter.join(2000);
        assertEquals("allow", result.get());
        assertTrue(approvals.list("s1").isEmpty(), "pending should clear after resolve");
    }

    @Test
    void timeoutReturnsDefaultAction() {
        Approvals approvals = new Approvals();
        long t0 = System.currentTimeMillis();
        String dec = approvals.await("s2", "run_command", "{}", 50, "deny");
        assertEquals("deny", dec);
        assertTrue(System.currentTimeMillis() - t0 >= 40);
        assertTrue(approvals.list("s2").isEmpty());
    }

    @Test
    void resolveUnknownIdReturnsFalse() {
        assertFalse(new Approvals().resolve("nope", "allow"));
    }
}
