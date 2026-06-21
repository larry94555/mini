package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shell hooks around tool use, like Claude Code's PreToolUse / PostToolUse hooks. Configured by an
 * optional hooks.json:
 *
 *   { "preToolUse":  [ { "match": "run_command", "command": "..." } ],
 *     "postToolUse": [ { "match": "edit_file",   "command": "..." } ],
 *     "userPromptSubmit": [ { "command": "..." } ],
 *     "stop":             [ { "command": "..." } ] }
 *
 *   - "match" is a tool name or "*" (preToolUse/postToolUse only; prompt/stop hooks always run).
 *   - A preToolUse hook that exits NON-ZERO blocks the tool (its output becomes the tool result).
 *   - A userPromptSubmit hook that exits NON-ZERO blocks the whole turn (its output is returned to the
 *     user instead of running the model); its stdout on a zero exit is injected as extra context.
 *   - postToolUse stdout is appended to the tool result (e.g. run a linter/formatter and show output).
 *   - stop hooks fire when a turn finishes; their stdout is appended to the final answer (e.g. notify).
 *   - Hooks receive context via env: IMINI_TOOL, IMINI_ARGS (JSON), IMINI_RESULT (post only),
 *     IMINI_PROMPT (userPromptSubmit), IMINI_ANSWER (stop).
 *
 * Off entirely if hooks.json is absent. Hook failures are logged and do NOT block (so a broken hook
 * can't brick the agent).
 */
@Component
public class HookService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HookService.class);


    public record Hook(String match, String command) {}

    private static final Path CONFIG = Path.of("hooks.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Hook> pre = new ArrayList<>();
    private final List<Hook> post = new ArrayList<>();
    private final List<Hook> userPromptSubmit = new ArrayList<>();
    private final List<Hook> stop = new ArrayList<>();

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        if (!Files.exists(CONFIG)) {
            log.info("[hooks] no hooks.json; hooks are off.");
            return;
        }
        try {
            Map<String, Object> cfg = mapper.readValue(Files.readAllBytes(CONFIG), Map.class);
            addHooks(pre, cfg.get("preToolUse"));
            addHooks(post, cfg.get("postToolUse"));
            addHooks(userPromptSubmit, cfg.get("userPromptSubmit"));
            addHooks(stop, cfg.get("stop"));
            log.info("[hooks] loaded " + pre.size() + " pre / " + post.size() + " post / "
                    + userPromptSubmit.size() + " userPromptSubmit / " + stop.size() + " stop hook(s).");
        } catch (Exception e) {
            log.warn("[hooks] could not read hooks.json: " + e.getMessage());
        }
    }

    private void addHooks(List<Hook> target, Object raw) {
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && m.get("command") != null) {
                    Object match = m.get("match");
                    target.add(new Hook(match == null ? "*" : String.valueOf(match), String.valueOf(m.get("command"))));
                }
            }
        }
    }

    /** Returns a block message if a pre-tool hook blocked the call, else null. */
    public String runPre(String tool, Map<String, Object> args) {
        for (Hook h : pre) {
            if (matches(h.match(), tool)) {
                Run r = exec(h.command(), env(tool, args, null));
                if (r != null && r.exit != 0) {
                    return "BLOCKED by pre-tool hook (exit " + r.exit + "): " + r.out.trim();
                }
            }
        }
        return null;
    }

    /** Returns combined stdout of matching post-tool hooks (may be empty). */
    public String runPost(String tool, Map<String, Object> args, String result) {
        StringBuilder sb = new StringBuilder();
        for (Hook h : post) {
            if (matches(h.match(), tool)) {
                Run r = exec(h.command(), env(tool, args, result));
                if (r != null && !r.out.isBlank()) sb.append(r.out.trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** The outcome of the userPromptSubmit hooks: either blocked (with a message) or allowed (with optional injected context). */
    public record PromptResult(boolean blocked, String message, String injectedContext) {}

    /**
     * Fires userPromptSubmit hooks before a turn runs. A non-zero exit BLOCKS the turn (its output is the
     * message returned to the user). On a zero exit, any stdout is collected as extra context to inject
     * ahead of the user's prompt. No hooks configured -> allowed with no injection.
     */
    public PromptResult runUserPromptSubmit(String prompt) {
        StringBuilder injected = new StringBuilder();
        for (Hook h : userPromptSubmit) {
            Run r = exec(h.command(), promptEnv(prompt));
            if (r == null) continue; // hook failure never blocks
            if (r.exit != 0) {
                return new PromptResult(true, "BLOCKED by userPromptSubmit hook (exit " + r.exit + "): " + r.out.trim(), "");
            }
            if (!r.out.isBlank()) injected.append(r.out.trim()).append("\n");
        }
        return new PromptResult(false, null, injected.toString().trim());
    }

    /** Fires stop hooks when a turn finishes; returns combined stdout to append to the final answer (may be empty). */
    public String runStop(String prompt, String answer) {
        StringBuilder sb = new StringBuilder();
        for (Hook h : stop) {
            Run r = exec(h.command(), stopEnv(prompt, answer));
            if (r != null && !r.out.isBlank()) sb.append(r.out.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    /** True if any userPromptSubmit hooks are configured (lets callers skip the wiring entirely when off). */
    public boolean hasPromptHooks() { return !userPromptSubmit.isEmpty(); }

    /** True if any stop hooks are configured. */
    public boolean hasStopHooks() { return !stop.isEmpty(); }

    private boolean matches(String match, String tool) {
        return "*".equals(match) || match.equals(tool);
    }

    private Map<String, String> env(String tool, Map<String, Object> args, String result) {
        Map<String, String> e = new HashMap<>();
        e.put("IMINI_TOOL", tool);
        try {
            e.put("IMINI_ARGS", mapper.writeValueAsString(args));
        } catch (Exception ignore) {
            // skip args on serialization failure
        }
        if (result != null) {
            e.put("IMINI_RESULT", result.length() > 2000 ? result.substring(0, 2000) : result);
        }
        return e;
    }

    private Map<String, String> promptEnv(String prompt) {
        Map<String, String> e = new HashMap<>();
        if (prompt != null) e.put("IMINI_PROMPT", prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt);
        return e;
    }

    private Map<String, String> stopEnv(String prompt, String answer) {
        Map<String, String> e = new HashMap<>();
        if (prompt != null) e.put("IMINI_PROMPT", prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt);
        if (answer != null) e.put("IMINI_ANSWER", answer.length() > 4000 ? answer.substring(0, 4000) : answer);
        return e;
    }

    private record Run(int exit, String out) {}

    private Run exec(String command, Map<String, String> env) {
        try {
            boolean win = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.environment().putAll(env);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            return new Run(code, out);
        } catch (Exception e) {
            log.warn("[hooks] failed to run '" + command + "': " + e.getMessage());
            return null; // don't block on hook failure
        }
    }
}
