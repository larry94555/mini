package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The catalog of custom subagents. Ships with read-only built-ins (explore, review, debug, research)
 * and also loads {@code agents/*.md} from the workspace (disk overrides a built-in of the same name).
 * Parsing/formatting are pure ({@link AgentLibrary}); only the filesystem walk lives here.
 */
@Component
public class AgentRegistry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRegistry.class);

    @Value("${agents.enabled:true}") private boolean enabled;
    @Value("${agents.dir:agents}") private String dir;

    private final Path root = Path.of("").toAbsolutePath().normalize();

    // Built-in agents. Tools are READ-ONLY by default so a delegated sub-run is safe under AUTO mode.
    private static final List<AgentLibrary.AgentDef> BUILTINS = List.of(
            new AgentLibrary.AgentDef("explore",
                    "Map the codebase and report where the relevant code lives.",
                    List.of("glob", "grep", "repo_tree", "read_many", "read_file", "view", "search_memory"), "",
                    "You are an exploration subagent. Given a question about this codebase, use the read-only "
                    + "navigation tools to locate the relevant files, then reply with a concise map: the key "
                    + "files/symbols and how they fit together. Do not modify anything. No questions; finish "
                    + "with plain text."),
            new AgentLibrary.AgentDef("review",
                    "Review code or a diff for correctness, safety, and clarity.",
                    List.of("read_file", "view", "grep", "repo_tree", "git_diff", "git_status"), "",
                    "You are a code-review subagent. Inspect the requested code/diff with the read-only tools "
                    + "and return prioritized findings (file/line, issue, concrete fix) plus a one-line verdict. "
                    + "Do not modify anything. No questions; finish with plain text."),
            new AgentLibrary.AgentDef("debug",
                    "Diagnose a bug and propose a minimal fix (read-only investigation).",
                    List.of("read_file", "view", "grep", "repo_tree", "git_diff", "git_status"), "",
                    "You are a debugging subagent. Investigate the reported problem with the read-only tools: "
                    + "localize the failing path, state one root-cause hypothesis, and propose the smallest fix. "
                    + "Do not modify anything yourself. No questions; finish with plain text."),
            new AgentLibrary.AgentDef("research",
                    "Search the web and summarize findings for a question.",
                    List.of("web_search", "web_fetch"), "",
                    "You are a research subagent. Search for relevant sources, open the most promising one or "
                    + "two, and write a concise answer that addresses the task and names the sources. No "
                    + "questions; finish with plain text."));

    /** All agents: built-ins merged with on-disk definitions (disk wins by name). */
    public List<AgentLibrary.AgentDef> list() {
        if (!enabled) return List.of();
        return AgentLibrary.merge(BUILTINS, fromDisk());
    }

    public AgentLibrary.AgentDef byName(String name) {
        if (name == null) return null;
        for (AgentLibrary.AgentDef a : list()) if (a.name().equalsIgnoreCase(name.trim())) return a;
        return null;
    }

    public boolean isAgentsCommand(String msg) {
        return msg != null && msg.trim().equals("/agents");
    }

    public AgentLibrary.Invocation parseCommand(String msg) {
        if (!enabled) return null;
        return AgentLibrary.parseCommand(msg);
    }

    public String report() {
        if (!enabled) return "Subagents are disabled (set agents.enabled=true).";
        return AgentLibrary.renderList(list());
    }

    private List<AgentLibrary.AgentDef> fromDisk() {
        Path d = root.resolve(dir).normalize();
        if (!d.startsWith(root) || !Files.isDirectory(d)) return List.of();
        List<AgentLibrary.AgentDef> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(d)) {
            s.filter(p -> p.toString().endsWith(".md")).sorted().forEach(p -> {
                try {
                    String stem = p.getFileName().toString().replaceFirst("\\.md$", "");
                    out.add(AgentLibrary.parse(Files.readString(p), stem));
                } catch (Exception e) {
                    log.warn("[agents] could not read " + p + ": " + e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[agents] could not list " + d + ": " + e.getMessage());
        }
        return out;
    }
}
