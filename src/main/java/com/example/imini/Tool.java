package com.example.imini;

import java.util.Map;
import java.util.function.Function;

/**
 * One tool the model can call. The {@code mutating} flag is what PermissionService keys off:
 * read-only tools run automatically; mutating ones require approval. The executor is plain Java --
 * THIS is where the harness actually does work in the real world. The model never runs any of it;
 * it only asks for it by name.
 */
public class Tool {

    public final String name;
    public final String description;
    public final Map<String, Object> parameters; // JSON Schema object
    public final boolean mutating;
    public final Function<Map<String, Object>, String> executor;

    public Tool(String name,
                String description,
                Map<String, Object> parameters,
                boolean mutating,
                Function<Map<String, Object>, String> executor) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.mutating = mutating;
        this.executor = executor;
    }
}
