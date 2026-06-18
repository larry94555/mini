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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextManager.class);


    static final String MEMORY_TAG = "[MEMORY]";

    private final LlamaClient llama;
    private final Metrics metrics;   // optional; null in unit tests
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${agent.compact-token-threshold:6000}")
    private int threshold;
    @Value("${agent.compact-keep-recent:6}")
    private int keepRecent;
    @Value("${agent.max-tool-result-chars:4000}")
    private int maxToolChars;

    // --- RLM-style bounded context fold (chunk -> summarize -> reduce -> recurse) ---
    // For inputs that VASTLY exceed the window, head+tail truncation loses the middle entirely. The fold
    // instead summarizes every chunk with the cheap summary model so all of it is read at least once
    // (lossy by COMPRESSION, not by DELETION). Off => prior head+tail behavior. See
    // docs/RECURSIVE_LANGUAGE_MODELS.md.
    @Value("${agent.fold-enabled:true}")
    private boolean foldEnabled;
    @Value("${agent.fold-threshold-chars:24000}")
    private int foldThresholdChars;   // only fold inputs larger than this (else cheap head+tail)
    @Value("${agent.fold-chunk-chars:8000}")
    private int foldChunkChars;       // size of each chunk fed to the summary model
    @Value("${agent.fold-target-chars:4000}")
    private int foldTargetChars;      // stop folding once the digest is at or below this
    @Value("${agent.fold-max-depth:2}")
    private int foldMaxDepth;         // recursion cap so the reduce always terminates

    public ContextManager(LlamaClient llama, Metrics metrics) {
        this.llama = llama;
        this.metrics = metrics;
    }

    /** Outcome of condensing a tool result: the (possibly reduced) text plus what happened, for trace
     *  events. {@code folded} is true only when the RLM-style fold ran (not for a plain head+tail trim). */
    public record Condensed(String text, boolean folded, int originalChars, int resultChars) {}

    /**
     * Trim an oversized tool result before it enters the conversation. See {@link #condenseToolResultTraced}
     * for details and trace metadata; this convenience overload returns just the text.
     */
    public String condenseToolResult(String result) {
        return condenseToolResultTraced(result).text();
    }

    /**
     * Condense like {@link #condenseToolResult} but report what happened.
     *
     * <p>For moderately large results this is a cheap head+tail trim (the middle is dropped). For inputs
     * that vastly exceed the window ({@code > agent.fold-threshold-chars}, when folding is enabled), it
     * instead performs a bounded RLM-style fold: chunk the input, summarize each chunk with the cheap
     * summary model, concatenate, and recurse until the digest fits. That keeps coverage of the whole
     * input (every region is read once) at the cost of resolution. If the fold fails for any reason, it
     * degrades gracefully to the head+tail trim.
     */
    public Condensed condenseToolResultTraced(String result) {
        if (result == null || result.length() <= maxToolChars) {
            int n = result == null ? 0 : result.length();
            return new Condensed(result, false, n, n);
        }
        int original = result.length();
        if (foldEnabled && result.length() > foldThresholdChars) {
            try {
                String folded = foldOversized(result, 0);
                if (metrics != null) {
                    metrics.noteFold("[fold] condensed " + original + " -> " + folded.length() + " chars");
                    metrics.addModelOutput(0); // fold uses the summary model; runs counted there
                }
                log.info("\n[fold] condensed a " + original + "-char input to "
                        + folded.length() + " chars");
                return new Condensed(folded, true, original, folded.length());
            } catch (Exception e) {
                if (metrics != null) metrics.inc("context_fold_fallback");
                log.warn("[fold] failed (" + e.getMessage() + "); falling back to head+tail trim");
            }
        }
        String trimmed = headTail(result, maxToolChars);
        return new Condensed(trimmed, false, original, trimmed.length());
    }

    /** Cheap, LM-free condense: keep the head and tail, drop the middle with a marker. */
    private String headTail(String result, int budgetChars) {
        if (result == null || result.length() <= budgetChars) return result;
        int head = (int) (budgetChars * 0.7);
        int tail = budgetChars - head;
        int omitted = result.length() - head - tail;
        return result.substring(0, head)
                + "\n...[" + omitted + " chars of tool output trimmed to save context]...\n"
                + result.substring(result.length() - tail);
    }

    /**
     * Bounded fold: chunk -> summarize each chunk with the summary model -> concatenate -> recurse if the
     * digest is still too big (up to {@code foldMaxDepth}). Returns a digest no larger than
     * {@code foldTargetChars} (a final head+tail trim guarantees the bound even if the model overruns).
     */
    private String foldOversized(String text, int depth) throws Exception {
        if (text.length() <= foldTargetChars) return text;
        if (depth >= foldMaxDepth) return headTail(text, foldTargetChars);

        List<String> chunks = chunkBy(text, foldChunkChars > 0 ? foldChunkChars : 8000);
        int perChunkTarget = Math.max(200, foldTargetChars / Math.max(1, chunks.size()));
        StringBuilder reduced = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            reduced.append(summarizeChunk(chunks.get(i), i + 1, chunks.size(), perChunkTarget)).append('\n');
        }
        String out = reduced.toString().trim();
        log.info("\n[fold] depth " + depth + ": " + text.length() + " chars -> "
                + chunks.size() + " chunks -> " + out.length() + " chars");

        if (out.length() > foldTargetChars && depth + 1 < foldMaxDepth) {
            out = foldOversized(out, depth + 1);
        }
        String body = out.length() > foldTargetChars ? headTail(out, foldTargetChars) : out;
        if (depth == 0) {
            return "[folded summary of a large tool result (" + text.length()
                    + " chars condensed; detail reduced)]\n" + body;
        }
        return body;
    }

    /** Summarize one chunk with the cheap summary model, asking it to preserve concrete facts. */
    private String summarizeChunk(String chunk, int idx, int total, int targetChars) throws Exception {
        List<Map<String, Object>> req = new ArrayList<>();
        req.add(role("system",
                "You compress one slice of a large tool result for an AI agent. Preserve concrete facts, "
                        + "names, numbers, code identifiers, and error messages; drop boilerplate and "
                        + "repetition. Be terse -- aim for about " + targetChars + " characters. Output only "
                        + "the compressed text, no preamble."));
        req.add(role("user", "SLICE " + idx + " of " + total + ":\n" + chunk));
        Map<String, Object> resp = llama.summaryChat(req);
        Object c = resp == null ? null : resp.get("content");
        String s = c == null ? "" : String.valueOf(c).trim();
        // If the model returns nothing usable, fall back to a head+tail trim of this chunk so we never
        // silently drop a whole region.
        return s.isBlank() ? headTail(chunk, targetChars) : s;
    }

    /** Pure helper: split {@code text} into consecutive chunks of at most {@code size} characters. */
    static List<String> chunkBy(String text, int size) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int n = Math.max(1, size);
        for (int i = 0; i < text.length(); i += n) {
            out.add(text.substring(i, Math.min(text.length(), i + n)));
        }
        return out;
    }

    public List<Map<String, Object>> compactIfNeeded(List<Map<String, Object>> messages,
                                                     String label, RunSink sink) throws Exception {
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

        if (metrics != null) metrics.noteCompact("[compact] folded " + toFold.size()
                + " older messages (~" + tokens + " tokens) into memory, kept " + (n - keepFrom) + " recent");
        log.info("\n[compaction:" + label + "] ~" + tokens + " tokens -> folded "
                + toFold.size() + " older messages into memory, kept " + (n - keepFrom) + " recent.");
        if (sink != null) {
            sink.log("[compact:" + label + "] folded " + toFold.size() + " older messages (~" + tokens
                    + " tokens) into the memory note, kept " + (n - keepFrom) + " recent");
        }

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

        Map<String, Object> resp = llama.summaryChat(req); // routed to the cheap summary model
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

    /** The token count above which compaction folds older history into the memory note. */
    public int compactThreshold() { return threshold; }

    /** The durable [MEMORY] note text in a message list, or null if there is none. Static so callers
     *  (e.g. cross-session seeding) can read it without a ContextManager instance. */
    public static String extractMemoryNote(List<Map<String, Object>> messages) {
        if (messages == null) return null;
        for (Map<String, Object> m : messages) {
            if ("user".equals(String.valueOf(m.get("role")))) {
                String c = String.valueOf(m.get("content"));
                if (c != null && c.startsWith(MEMORY_TAG)) return c.substring(MEMORY_TAG.length()).trim();
            }
        }
        return null;
    }

    /** Build a [MEMORY] message from a note (for seeding a fresh session from durable memory). */
    public static Map<String, Object> memoryMessageFor(String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", "user");
        m.put("content", MEMORY_TAG + "\n" + (note == null ? "" : note));
        return m;
    }

    private Map<String, Object> role(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
