package com.example.imini.ext;

import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import com.example.imini.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USE CASE 1 — add a custom in-process tool the model can call.
 *
 * <p>This is the flagship extension point: a validated, permission-gated {@link Tool} that runs
 * IN-PROCESS (unlike an MCP server) and is added WITHOUT editing core Java (unlike a built-in tool).
 * Drop this file in {@code src/main/java/com/example/imini/ext/}, rebuild, and the model can call
 * {@code word_count} immediately.
 *
 * <p>The tool is read-only ({@code mutating=false}) so it runs without an approval prompt. If it
 * changed the world you would pass {@code mutating=true} and it would route through PermissionService
 * exactly like {@code write_file}.
 */
@Component
public class WordCountExtension implements Extension {

    @Override
    public String name() {
        return "word-count";
    }

    @Override
    public List<Tool> tools(ExtensionContext ctx) {
        // JSON-schema for the arguments: one required string, "text".
        Map<String, Object> textProp = new LinkedHashMap<>();
        textProp.put("type", "string");
        textProp.put("description", "The text to measure.");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", textProp);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("text"));

        Tool wordCount = new Tool(
                "word_count",
                "Count the words, characters, and lines in a piece of text. Read-only.",
                schema,
                /* mutating = */ false,
                args -> {
                    String text = String.valueOf(args.getOrDefault("text", ""));
                    int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
                    int chars = text.length();
                    long lines = text.isEmpty() ? 0 : text.lines().count();
                    return "words=" + words + " chars=" + chars + " lines=" + lines;
                });

        return List.of(wordCount);
    }
}
