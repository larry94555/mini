package com.example.imini;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Additional deterministic schema validation coverage. */
class SchemaValidatorEdgeCaseTest {

    @Test
    void acceptsObjectAndArrayTypesWhenTheyMatch() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "meta", Map.of("type", "object"),
                        "items", Map.of("type", "array")),
                "required", List.of("meta", "items"));

        String error =
                SchemaValidator.validate(
                        "todo_write",
                        schema,
                        Map.of("meta", Map.of("source", "test"), "items", List.of("a", "b")));

        assertNull(error);
    }

    @Test
    void rejectsWrongArrayType() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("items", Map.of("type", "array")),
                "required", List.of("items"));

        String error = SchemaValidator.validate("todo_write", schema, Map.of("items", "not-an-array"));

        assertNotNull(error);
        assertTrue(error.contains("should be a array") || error.contains("should be an array"), error);
    }

    @Test
    void nullSchemaMeansNoValidation() {
        assertNull(SchemaValidator.validate("unknown", null, Map.of("anything", 1)));
    }
}
