package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 2 layered context management:
 *
 *   1. ACCURATE TOKENS -- countTokens() asks llama-server's /tokenize for the real count instead of
 *      guessing chars/4 (it falls back to chars/4 only if the endpoint is unavailable).
 *   2. TRIM LARGE TOOL OUTPUTS -- condenseToolResult() shrinks an oversized tool result (a giant
 *      file or web page) to head+tail before it ever enters the history, so one big read can't blow
 *      the window.
 *   3. DURABLE MEMORY -- compaction keeps a single evolving "[MEMORY]" note (right after the system
 *      message) that accumulates durable facts/decisions/goals and survives every future compaction,
 *      rather than repeatedly re-summarizing from scratch.
 */
@Component
public class ContextManager {

    static final String MEMORY_TAG = "[MEMORY]";

    private final LlamaClient llama;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.compact-token-threshold:6000}")
    private int threshold;
    @Value("${agent.compact-keep-recent:6}")
    private int keepRecent;
    @Value("${agent.max-tool-result-chars:4000}")
    private int maxToolChars;

    public ContextManager(LlamaClient llama) {
        this.llama = llama;
    }

    /** Trim an oversized tool result to head + tail before it enters the conversation. */
    public String condenseToolResult(String result) {
        if (result == null || result.length() <= maxToolChars) return result;
        int head = (int) (maxToolChars * 0.7);
        int tail = maxToolChars - head;
        int omitted = result.length() - head - tail;
        return result.substring(0, head)
                + "\n...[" + omitted + " chars of tool output trimmed to save context]...\n"
                + result.substring(result.length() - tail);
    }

    public List<Map<String, Object>> compactIfNeeded(List<Map<String, Object>> messages,
                                                     String label) throws Exception {
        int tokens = countTokens(messages);
        if (tokens < threshold || messages.size() <= keepRecent + 2) {
            return messages;
        }

        Map<String, Object> system = messages.get(0);
        boolean hasMemory = messages.size() > 1 && isMemory(messages.get(1));
        String oldMemory = hasMemory ? stripTag(content(messages.get(1))) : "";
        int bodyStart = hasMemory ? 2 : 1;

        int n = messages.size();
        int keepFrom = Math.max(bodyStart, n - keepRecent);
        while (keepFrom < n && "tool".equals(String.valueOf(messages.get(keepFrom).get("role")))) {
            keepFrom++;
        }
        if (keepFrom <= bodyStart) return messages; // nothing safe to fold in

        List<Map<String, Object>> toFold = messages.subList(bodyStart, keepFrom);
        String newMemory = updateMemory(oldMemory, toFold);

        System.out.println("\n[compaction:" + label + "] ~" + tokens + " tokens -> folded "
                + toFold.size() + " older messages into memory, kept " + (n - keepFrom) + " recent.");

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(system);
        out.add(memoryMessage(newMemory));
        out.addAll(new ArrayList<>(messages.subList(keepFrom, n)));
        return out;
    }

    /** Real token count via llama-server; chars/4 fallback if /tokenize is unreachable. */
    private int countTokens(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : messages) {
            sb.append(m.get("role")).append(": ");
            Object c = m.get("content");
            if (c != null) sb.append(c);
            Object tc = m.get("tool_calls");
            if (tc != null) sb.append(' ').append(tc);
            sb.append('\n');
        }
        int real = llama.countTokens(sb.toString());
        return real > 0 ? real : sb.length() / 4;
    }

    private String updateMemory(String oldMemory, List<Map<String, Object>> folded) throws Exception {
        StringBuilder transcript = new StringBuilder();
        for (Map<String, Object> m : folded) {
            transcript.append(m.get("role")).append(": ");
            Object c = m.get("content");
            if (c != null) transcript.append(c);
            Object tc = m.get("tool_calls");
            if (tc != null) transcript.append("  <tool_calls: ").append(tc).append('>');
            transcript.append('\n');
        }

        List<Map<String, Object>> req = new ArrayList<>();
        req.add(role("system",
                "You maintain concise running notes for an AI agent. Given the existing notes and a "
                        + "new slice of conversation, output UPDATED notes capturing durable facts, "
                        + "decisions made, and goals still unfinished. Keep it short. Output only the notes."));
        req.add(role("user", "EXISTING NOTES:\n" + (oldMemory.isBlank() ? "(none yet)" : oldMemory)
                + "\n\nNEW CONVERSATION:\n" + transcript));

        Map<String, Object> resp = llama.chat(req, null); // no tools while summarizing
        Object c = resp.get("content");
        String notes = c == null ? "" : String.valueOf(c).trim();
        return notes.isBlank() ? (oldMemory.isBlank() ? "(no notes)" : oldMemory) : notes;
    }

    private boolean isMemory(Map<String, Object> m) {
        return "user".equals(String.valueOf(m.get("role")))
                && String.valueOf(m.get("content")).startsWith(MEMORY_TAG);
    }

    private String stripTag(String content) {
        String s = content == null ? "" : content;
        return s.startsWith(MEMORY_TAG) ? s.substring(MEMORY_TAG.length()).trim() : s;
    }

    private String content(Map<String, Object> m) {
        Object c = m.get("content");
        return c == null ? "" : String.valueOf(c);
    }

    private Map<String, Object> memoryMessage(String notes) {
        return role("user", MEMORY_TAG + "\n" + notes);
    }

    private Map<String, Object> role(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
