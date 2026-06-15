package com.example.imini;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom slash commands, like Claude Code's. Each .md file in the commands/ folder defines a command
 * named after the file; its contents are a prompt template where $ARGS (or $ARGUMENTS) is replaced
 * by whatever the user typed after the command. The expanded prompt is what the model sees.
 *
 *   commands/explain.md  ->  "/explain recursion"  expands to that template with $ARGS = "recursion".
 *
 * "/help" or "/commands" lists what's available. Unknown slash text passes through unchanged.
 */
@Component
public class SlashCommands {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SlashCommands.class);


    private static final Path DIR = Path.of("commands");
    private final Map<String, String> templates = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        if (!Files.isDirectory(DIR)) {
            log.info("[commands] no commands/ folder; slash commands are off.");
            return;
        }
        try (var s = Files.list(DIR)) {
            s.filter(p -> p.toString().endsWith(".md")).forEach(p -> {
                try {
                    String name = p.getFileName().toString().replaceAll("\\.md$", "");
                    templates.put(name, Files.readString(p).trim());
                } catch (Exception ignore) {
                    // skip unreadable command file
                }
            });
        } catch (Exception e) {
            log.warn("[commands] could not read commands/: " + e.getMessage());
        }
        log.info("[commands] loaded " + templates.size() + " slash command(s): " + templates.keySet());
    }

    public boolean isHelp(String msg) {
        String m = msg == null ? "" : msg.trim();
        return m.equals("/help") || m.equals("/commands");
    }

    public String help() {
        StringBuilder sb = new StringBuilder("Available slash commands:\n");
        sb.append("  /memory            show which project-memory files are loaded\n");
        sb.append("  /init              scan the repo and draft/update CLAUDE.md\n");
        if (templates.isEmpty()) {
            sb.append("  (none -- add .md files to the commands/ folder)");
        } else {
            for (String name : templates.keySet()) sb.append("  /").append(name).append(" <args>\n");
            sb.append("\nEach expands to its template with $ARGS replaced by the text after the command.");
        }
        return sb.toString();
    }

    /** Expand "/name args" via commands/name.md; non-slash or unknown commands pass through. */
    public String expand(String msg) {
        if (msg == null || !msg.trim().startsWith("/")) return msg;
        String trimmed = msg.trim();
        int sp = trimmed.indexOf(' ');
        String name = (sp < 0 ? trimmed.substring(1) : trimmed.substring(1, sp)).trim();
        String args = sp < 0 ? "" : trimmed.substring(sp + 1).trim();
        String template = templates.get(name);
        if (template == null) return msg;
        return template.replace("$ARGS", args).replace("$ARGUMENTS", args);
    }
}
