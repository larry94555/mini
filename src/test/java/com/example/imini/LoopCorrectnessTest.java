package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Deterministic harness checks that do not require a live model.
 *
 * <p>These tests focus on the harness contracts that matter most for a Claude Code-style loop:
 * schema validation, workspace confinement, retry behavior, and grammar generation.
 */
class LoopCorrectnessTest {

    private Map<String, Object> stringPathSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"));
    }

    @Test
    void schemaAcceptsValidArgs() {
        assertNull(SchemaValidator.validate("read_file", stringPathSchema(), Map.of("path", "notes.txt")));
    }

    @Test
    void schemaRejectsMissingRequiredField() {
        String err = SchemaValidator.validate("read_file", stringPathSchema(), Map.of());
        assertNotNull(err);
        assertTrue(err.contains("missing required field 'path'"), err);
    }

    @Test
    void schemaRejectsWrongType() {
        String err = SchemaValidator.validate("read_file", stringPathSchema(), Map.of("path", 123));
        assertNotNull(err);
        assertTrue(err.contains("should be a string"), err);
    }

    @Test
    void confinementAllowsPathsWithinWorkspace() {
        Path root = Path.of("/work/project");

        assertTrue(PermissionService.isWithin(root, "notes.txt"));
        assertTrue(PermissionService.isWithin(root, "src/main/App.java"));
        assertTrue(PermissionService.isWithin(root, "sub/dir/../dir/file.txt"));
    }

    @Test
    void confinementRejectsPathTraversalAndAbsolutePaths() {
        Path root = Path.of("/work/project");

        assertFalse(PermissionService.isWithin(root, "../escape.txt"));
        assertFalse(PermissionService.isWithin(root, "sub/../../escape.txt"));
        assertFalse(PermissionService.isWithin(root, "/etc/passwd"));
    }

    @Test
    void retryRecoversAfterTransientFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        String result = Retry.withBackoff(
                3,
                1,
                () -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new java.io.IOException("transient");
                    }
                    return "ok";
                });

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retryDoesNotRetryClientErrors() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                IllegalStateException.class,
                () -> Retry.withBackoff(
                        3,
                        1,
                        () -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("bad request");
                        }));

        assertEquals(1, calls.get());
    }

    @Test
    void grammarIncludesAvailableToolNames() {
        List<Map<String, Object>> specs = List.of(
                Map.of("function", Map.of("name", "read_file")),
                Map.of("function", Map.of("name", "view")),
                Map.of("function", Map.of("name", "todo_write")));

        String grammar = GrammarBuilder.fromTools(specs);

        assertNotNull(grammar);
        assertTrue(grammar.contains("read_file"), grammar);
        assertTrue(grammar.contains("view"), grammar);
        assertTrue(grammar.contains("todo_write"), grammar);
        assertTrue(grammar.contains("tool-call"), grammar);
    }

    @Test
    void grammarIsNullWhenNoToolsAreAvailable() {
        assertNull(GrammarBuilder.fromTools(List.of()));
    }
}
