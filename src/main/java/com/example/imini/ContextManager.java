package com.example.imini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the conversation inside the model's context window. When the estimated token count crosses
 * a threshold (default ~6000, comfortably under the 8192 ctx-size), it summarizes the older turns
 * into a single short message and keeps the most recent turns verbatim.
 *
 * Two safety details worth noticing:
 *   - it never summarizes the system prompt (index 0),
 *   - it never lets the kept "tail" start on a 'tool' message, which would orphan a tool result
 *     from the assistant tool-call that produced it and break the chat template.
 *
 * This is a tiny version of what a real harness does continuously; the chars/4 token estimate is
 * deliberately crude so the logic stays readable.
 */
@Component
public class ContextManager {

    private final LlamaClient llama;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.compact-token-threshold:6000}")
    private int threshold;

    @Value("${agent.compact-keep-recent:6}")
    private int keepRecent;

    public ContextManager(LlamaClient llama) {
        this.llama = llama;
    }

    public List<Map<String, Object>> compactIfNeeded(List<Map<String, Object>> messages,
                                                     String label) throws Exception {
        int tokens = estimateTokens(messages);
        if (tokens < threshold || messages.size() <= keepRecent + 2) {
            return messages;
        }

        int n = messages.size();
        int keepFrom = Math.max(1, n - keepRecent);
        // do not start the kept tail on a tool result
        while (keepFrom < n && "tool".equals(String.valueOf(messages.get(keepFrom).get("role")))) {
            keepFrom++;
        }
        if (keepFrom <= 1) return messages; // nothing safe to summarize

        List<Map<String, Object>> toSummarize = messages.subList(1, keepFrom);
        String summary = summarize(toSummarize);

        System.out.println("\n[compaction:" + label + "] ~" + tokens + " tokens -> summarized "
                + toSummarize.size() + " older messages, kept " + (n - keepFrom) + " recent.");

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(messages.get(0)); // system

        Map<String, Object> summaryMsg = new LinkedHashMap<>();
        summaryMsg.put("role", "user");
        summaryMsg.put("content", "[Summary of earlier conversation, compacted to save context]\n" + summary);
        out.add(summaryMsg);

        out.addAll(new ArrayList<>(messages.subList(keepFrom, n)));
        return out;
    }

    private String summarize(List<Map<String, Object>> msgs) throws Exception {
        StringBuilder transcript = new StringBuilder();
        for (Map<String, Object> m : msgs) {
            transcript.append(m.get("role")).append(": ");
            Object c = m.get("content");
            if (c != null) transcript.append(c);
            Object tc = m.get("tool_calls");
            if (tc != null) transcript.append("  <tool_calls: ").append(tc).append(">");
            transcript.append("\n");
        }

        List<Map<String, Object>> req = new ArrayList<>();
        req.add(role("system",
                "You compress conversation history. Summarize the exchange below into a few sentences "
                        + "that capture the facts learned, decisions made, and any goals still unfinished. "
                        + "Be concise. Output only the summary."));
        req.add(role("user", transcript.toString()));

        Map<String, Object> resp = llama.chat(req, null); // no tools while summarizing
        Object c = resp.get("content");
        return c == null ? "(summary unavailable)" : String.valueOf(c);
    }

    private Map<String, Object> role(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Rough chars/4 token estimate; good enough to trigger compaction. */
    private int estimateTokens(List<Map<String, Object>> messages) {
        int chars = 0;
        for (Map<String, Object> m : messages) {
            Object c = m.get("content");
            if (c != null) chars += String.valueOf(c).length();
            Object tc = m.get("tool_calls");
            if (tc != null) chars += String.valueOf(tc).length();
            chars += 8; // per-message overhead
        }
        return chars / 4;
    }
}
