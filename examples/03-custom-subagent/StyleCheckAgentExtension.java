package com.example.imini.ext;

import com.example.imini.AgentLibrary;
import com.example.imini.Extension;
import com.example.imini.ExtensionContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * USE CASE 3 — contribute a named, tool-scoped subagent in code.
 *
 * <p>Equivalent to an {@code agents/<name>.md} file, but built in Java so it can ship inside an
 * extension bundle. The subagent runs in its own isolated loop and returns only its final answer; it is
 * scoped to a read-only tool set so it is safe to delegate to even under AUTO mode.
 *
 * <p>Invoke it with {@code /agent stylecheck <path or code>}, or let the model call {@code
 * delegate_agent}. On-disk {@code agents/stylecheck.md} still overrides this if present (disk wins).
 */
@Component
public class StyleCheckAgentExtension implements Extension {

    @Override
    public String name() {
        return "stylecheck-agent";
    }

    @Override
    public List<AgentLibrary.AgentDef> agents(ExtensionContext ctx) {
        AgentLibrary.AgentDef stylecheck = new AgentLibrary.AgentDef(
                "stylecheck",
                "Check code against this team's style rules (read-only).",
                List.of("read_file", "view", "grep", "repo_tree"),   // read-only tool scope
                "",                                                    // default model
                "You are a style-check subagent. Using only the read-only tools, inspect the requested "
                        + "file(s) and report violations of these house rules: 4-space indentation, no wildcard "
                        + "imports, no lines over 120 characters, and a Javadoc on every public class. Return a "
                        + "short list of file:line -> issue, then a one-line verdict. Do not modify anything. "
                        + "No questions; finish with plain text.");
        return List.of(stylecheck);
    }
}
