package com.example.imini;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Pure token-budget math for keeping a request under llama-server's context window. The server rejects a
 * call when the prompt exceeds {@code n_ctx} ("exceeds the available context size"); this class shrinks
 * the outgoing message list so that never happens, deterministically and testably.
 *
 * <p><b>How it stays under budget (smart, structure-preserving):</b>
 * <ol>
 *   <li>truncate oversized individual messages (a big tool result / pasted file) to a fair share, with a
 *       marker -- this is the "summarize/condense" step;</li>
 *   <li>if still over, drop the oldest middle messages (keeping the system message and the latest
 *       message, which carries the actual request);</li>
 *   <li>as a last resort, truncate the latest message (and then the system message) so the call always
 *       fits.</li>
 * </ol>
 * The token counter is injected, so callers can use llama-server's real {@code /tokenize} count or a
 * cheap {@code chars/4} estimate; tests use a deterministic counter.
 */
public final class TokenBudget {

    private TokenBudget() {}

    /** Marker appended where content was cut to fit the budget. */
    public static final String TRIM_MARKER = "\n...[trimmed to fit the token budget]...";

    /** Cheap, dependency-free estimate: ~4 characters per token (min 1 for non-empty text). */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    /** Estimate for a whole message list (content + a small per-message overhead for role/framing). */
    public static int estimateMessages(List<Map<String, Object>> messages) {
        return totalTokens(messages, TokenBudget::estimate);
    }

    /** Result of fitting: the (possibly reduced) messages plus what changed. */
    public record Fitted(List<Map<String, Object>> messages, int truncated, int dropped, int beforeTokens,
                         int afterTokens) {
        public boolean changed() { return truncated > 0 || dropped > 0; }
    }

    private static final int PER_MESSAGE_OVERHEAD = 4; // rough role/delimiter cost per message

    private static int totalTokens(List<Map<String, Object>> messages, ToIntFunction<String> counter) {
        int t = 0;
        for (Map<String, Object> m : messages) t += counter.applyAsInt(content(m)) + PER_MESSAGE_OVERHEAD;
        return t;
    }

    private static String content(Map<String, Object> m) {
        Object c = m == null ? null : m.get("content");
        return c == null ? "" : String.valueOf(c);
    }

    /**
     * Reduce {@code messages} so the counted total is at most {@code cap}. Always keeps the system message
     * (index 0, when present) and the last message. Returns a NEW list; inputs are not mutated.
     */
    public static Fitted fit(List<Map<String, Object>> messages, int cap, ToIntFunction<String> counter) {
        List<Map<String, Object>> work = new ArrayList<>();
        for (Map<String, Object> m : messages) work.add(new java.util.LinkedHashMap<>(m));
        int before = totalTokens(work, counter);
        if (before <= cap || work.isEmpty()) return new Fitted(work, 0, 0, before, before);

        int n = work.size();
        int sys = (n > 0 && "system".equals(String.valueOf(work.get(0).get("role")))) ? 1 : 0;
        int last = n - 1;
        int truncated = 0, dropped = 0;

        // Pass 1: truncate oversized middle messages to a fair share of the budget.
        int share = Math.max(64, cap / Math.max(1, n));
        for (int i = sys; i < last; i++) {
            String c = content(work.get(i));
            if (counter.applyAsInt(c) > share) {
                work.get(i).put("content", truncateToTokens(c, share, counter));
                truncated++;
                if (totalTokens(work, counter) <= cap) {
                    return new Fitted(work, truncated, dropped, before, totalTokens(work, counter));
                }
            }
        }

        // Pass 2: drop oldest middle messages (never the system message or the last message).
        while (totalTokens(work, counter) > cap && work.size() > sys + 1) {
            int removable = -1;
            for (int i = sys; i < work.size() - 1; i++) { removable = i; break; }
            if (removable < 0) break;
            work.remove(removable);
            dropped++;
        }
        if (totalTokens(work, counter) <= cap) {
            return new Fitted(work, truncated, dropped, before, totalTokens(work, counter));
        }

        // Pass 3: last resort -- truncate the last message, then the system message.
        int lastIdx = work.size() - 1;
        int reserveForOthers = sys == 1 ? Math.min(counter.applyAsInt(content(work.get(0))) + PER_MESSAGE_OVERHEAD, cap / 2) : 0;
        int lastTarget = Math.max(32, cap - reserveForOthers - PER_MESSAGE_OVERHEAD);
        work.get(lastIdx).put("content", truncateToTokens(content(work.get(lastIdx)), lastTarget, counter));
        truncated++;
        if (sys == 1 && totalTokens(work, counter) > cap) {
            int sysTarget = Math.max(16, cap - counter.applyAsInt(content(work.get(lastIdx))) - 2 * PER_MESSAGE_OVERHEAD);
            work.get(0).put("content", truncateToTokens(content(work.get(0)), sysTarget, counter));
            truncated++;
        }
        return new Fitted(work, truncated, dropped, before, totalTokens(work, counter));
    }

    /** Truncate {@code text} so {@code counter(result) <= targetTokens}, appending {@link #TRIM_MARKER}. */
    public static String truncateToTokens(String text, int targetTokens, ToIntFunction<String> counter) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (counter.applyAsInt(text) <= targetTokens) return text;
        int markerTokens = counter.applyAsInt(TRIM_MARKER);
        int bodyTarget = Math.max(0, targetTokens - markerTokens);
        // proportional first cut, then shrink until it fits
        int keep = (int) (text.length() * (bodyTarget / (double) Math.max(1, counter.applyAsInt(text))));
        keep = Math.max(0, Math.min(text.length(), keep));
        String cut = text.substring(0, keep);
        while (keep > 0 && counter.applyAsInt(cut) > bodyTarget) {
            keep = Math.max(0, keep - Math.max(1, keep / 8));
            cut = text.substring(0, keep);
        }
        return cut + TRIM_MARKER;
    }
}
