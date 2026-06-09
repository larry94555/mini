package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic harness eval suite (no model required) -- the CI-able core of "loop correctness".
 * Encodes the roadmap's questions: does it accept the RIGHT tool call, STAY in the workspace, and
 * RECOVER from bad input / transient failures?
 */
class LoopCorrectnessTest {

    private Map<String, Object> stringPathSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"));
    }

    // --- schema validation: right args accepted, bad args rejected (recover) ---

    @Test
    void schemaAcceptsValidArgs() {
        assertNull(SchemaValidator.validate("read_file", stringPathSchema(), Map.of("path", "notes.txt")));
    }

    @Test
    void schemaRejectsMissingRequired() {
        String err = SchemaValidator.validate("read_file", stringPathSchema(), Map.of());
        assertNotNull(err);
        assertTrue(err.contains("missing required"), err);
    }

    @Test
    void schemaRejectsWrongType() {
        String err = SchemaValidator.validate("read_file", stringPathSchema(), Map.of("path", 123));
        assertNotNull(err);
        assertTrue(err.contains("should be a string"), err);
    }

    // --- workspace confinement: stays in workspace ---

    @Test
    void confinementStaysInWorkspace() {
        Path root = Path.of("/work/project");
        assertTrue(PermissionService.isWithin(root, "notes.txt"));
        assertTrue(PermissionService.isWithin(root, "sub/dir/x.java"));
        assertFalse(PermissionService.isWithin(root, "../escape.txt"));
        assertFalse(PermissionService.isWithin(root, "/etc/passwd"));
    }

    // --- retries with backoff: recover from transient failures ---

    @Test
    void retryRecoversAfterTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String result = Retry.withBackoff(3, 1, () -> {
            if (calls.incrementAndGet() < 3) throw new java.io.IOException("transient");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retryDoesNotRetryClientErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> Retry.withBackoff(3, 1, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("bad request");
        }));
        assertEquals(1, calls.get()); // non-IOException is not retried
    }

    // --- constrained decoding: grammar names the available tools ---

    @Test
    void grammarIncludesToolNames() {
        List<Map<String, Object>> specs = List.of(
                Map.of("function", Map.of("name", "read_file")),
                Map.of("function", Map.of("name", "view")));
        String g = GrammarBuilder.fromTools(specs);
        assertNotNull(g);
        assertTrue(g.contains("read_file"), g);
        assertTrue(g.contains("view"), g);
        assertTrue(g.contains("tool-call"), g);
    }

    @Test
    void grammarNullWhenNoTools() {
        assertNull(GrammarBuilder.fromTools(List.of()));
    }
}
