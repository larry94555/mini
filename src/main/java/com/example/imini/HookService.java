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
 *     "postToolUse": [ { "match": "edit_file",   "command": "..." } ] }
 *
 *   - "match" is a tool name or "*".
 *   - A preToolUse hook that exits NON-ZERO blocks the tool (its output becomes the tool result).
 *   - postToolUse stdout is appended to the tool result (e.g. run a linter/formatter and show output).
 *   - Hooks receive context via env: IMINI_TOOL, IMINI_ARGS (JSON), IMINI_RESULT (post only).
 *
 * Off entirely if hooks.json is absent. Hook failures are logged and do NOT block (so a broken hook
 * can't brick the agent).
 */
@Component
public class HookService {

    public record Hook(String match, String command) {}

    private static final Path CONFIG = Path.of("hooks.json");

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Hook> pre = new ArrayList<>();
    private final List<Hook> post = new ArrayList<>();

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        if (!Files.exists(CONFIG)) {
            System.out.println("[hooks] no hooks.json; hooks are off.");
            return;
        }
        try {
            Map<String, Object> cfg = mapper.readValue(Files.readAllBytes(CONFIG), Map.class);
            addHooks(pre, cfg.get("preToolUse"));
            addHooks(post, cfg.get("postToolUse"));
            System.out.println("[hooks] loaded " + pre.size() + " pre / " + post.size() + " post hook(s).");
        } catch (Exception e) {
            System.out.println("[hooks] could not read hooks.json: " + e.getMessage());
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
            System.out.println("[hooks] failed to run '" + command + "': " + e.getMessage());
            return null; // don't block on hook failure
        }
    }
}
