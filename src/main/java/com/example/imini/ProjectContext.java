package com.example.imini;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Project memory, like Claude Code's CLAUDE.md. If a project-instructions file exists in the working
 * directory, its contents are appended to the system prompt so the agent knows your conventions,
 * commands, and preferences. Read fresh each time, so editing the file takes effect without a restart
 * (for one-shot /ask; for /chat it is captured when the session starts).
 *
 * Looks for IMINI.md, then CLAUDE.md, then AGENTS.md.
 */
@Component
public class ProjectContext {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectContext.class);


    private static final List<String> NAMES = List.of("IMINI.md", "CLAUDE.md", "AGENTS.md");

    public String addendum() {
        for (String name : NAMES) {
            Path p = Path.of(name);
            if (Files.exists(p)) {
                try {
                    String content = Files.readString(p).trim();
                    if (!content.isEmpty()) {
                        return "\n\n--- Project instructions (from " + name + ") ---\n" + content;
                    }
                } catch (Exception e) {
                    log.warn("[project] could not read " + name + ": " + e.getMessage());
                }
            }
        }
        return "";
    }
}
