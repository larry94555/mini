package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workspace id used to scope durable memory per project is stable and well-formed. */
class MemoryWorkspaceTest {

    @Test
    void workspaceIdIsStableAndShort() {
        String a = MemoryStore.workspaceId();
        String b = MemoryStore.workspaceId();
        assertNotNull(a);
        assertEquals(a, b, "workspace id must be stable within a process");
        assertEquals(12, a.length(), "expected a 12-hex-char id");
        assertTrue(a.matches("[0-9a-f]{12}"), "hex id, got " + a);
    }
}
