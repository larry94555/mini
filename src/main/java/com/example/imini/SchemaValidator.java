package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON-schema validation of tool-call arguments against a Tool's {@code parameters}
 * schema. It is intentionally small: it checks that required fields are present and that provided
 * fields have roughly the right JSON type. On failure it returns a human-readable message that the
 * engine feeds back to the model as the tool result, so the model can correct itself and retry --
 * which is how the loop "recovers from errors" instead of executing a malformed call.
 */
public final class SchemaValidator {

    private SchemaValidator() {}

    /** Returns null if args satisfy the schema; otherwise a corrective error message for the model. */
    @SuppressWarnings("unchecked")
    public static String validate(String toolName, Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null) return null;

        Object propsObj = schema.get("properties");
        Map<String, Object> props = (propsObj instanceof Map) ? (Map<String, Object>) propsObj : Map.of();

        Object reqObj = schema.get("required");
        if (reqObj instanceof List<?> req) {
            for (Object r : req) {
                String key = String.valueOf(r);
                if (args == null || !args.containsKey(key) || args.get(key) == null) {
                    return "INVALID_ARGS for '" + toolName + "': missing required field '" + key
                            + "'. Expected fields: " + describe(props) + ". Call it again with that field.";
                }
            }
        }

        if (args != null) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                Object spec = props.get(e.getKey());
                if (spec instanceof Map<?, ?> sm) {
                    Object type = ((Map<String, Object>) sm).get("type");
                    String err = checkType(toolName, e.getKey(), type == null ? null : String.valueOf(type), e.getValue());
                    if (err != null) return err;
                }
            }
        }
        return null;
    }

    private static String checkType(String tool, String field, String type, Object val) {
        if (type == null || val == null) return null;
        boolean ok = switch (type) {
            case "string" -> val instanceof String;
            case "integer" -> val instanceof Integer || val instanceof Long
                    || (val instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()));
            case "number" -> val instanceof Number;
            case "boolean" -> val instanceof Boolean;
            case "array" -> val instanceof List;
            case "object" -> val instanceof Map;
            default -> true; // unknown/!unconstrained type -> accept
        };
        if (!ok) {
            return "INVALID_ARGS for '" + tool + "': field '" + field + "' should be a " + type
                    + " but got " + val.getClass().getSimpleName() + ". Fix the type and call it again.";
        }
        return null;
    }

    private static String describe(Map<String, Object> props) {
        return props.isEmpty() ? "(none)" : String.join(", ", props.keySet());
    }
}
