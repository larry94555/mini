package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RLM-style bounded context fold in {@link ContextManager}: chunk -> summarize -> reduce, with
 * graceful fallback. Uses a deterministic fake summary model so the test needs no llama-server.
 */
class ContextFoldTest {

    /** A LlamaClient test double: deterministic summaries, optional failure, no network. */
    static class FakeLlama extends LlamaClient {
        int calls = 0;
        boolean fail = false;
        FakeLlama() { super(null); }

        @Override public int countTokens(String t) { return t == null ? 0 : t.length() / 4; }

        @Override public Map<String, Object> summaryChat(List<Map<String, Object>> msgs) throws Exception {
            calls++;
            if (fail) throw new RuntimeException("boom");
            String user = String.valueOf(msgs.get(msgs.size() - 1).get("content"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("content", "SUM(" + user.length() + ")");   // short, deterministic
            return m;
        }
    }

    private static void set(ContextManager cm, String field, Object value) throws Exception {
        Field f = ContextManager.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(cm, value);
    }

    private static ContextManager manager(FakeLlama llama, boolean foldEnabled) throws Exception {
        ContextManager cm = new ContextManager(llama, null);
        set(cm, "maxToolChars", 100);
        set(cm, "foldEnabled", foldEnabled);
        set(cm, "foldThresholdChars", 200);
        set(cm, "foldChunkChars", 100);
        set(cm, "foldTargetChars", 120);
        set(cm, "foldMaxDepth", 2);
        return cm;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    void chunkBy_splitsAndReassemblesExactly() {
        List<String> parts = ContextManager.chunkBy("abcdefg", 3);
        assertEquals(List.of("abc", "def", "g"), parts);
        assertEquals("abcdefg", String.join("", parts));
        assertTrue(ContextManager.chunkBy("", 3).isEmpty());
        assertEquals(1, ContextManager.chunkBy("x", 100).size());
    }

    @Test
    void smallResult_isReturnedUnchanged() throws Exception {
        ContextManager cm = manager(new FakeLlama(), true);
        String small = "just a little output";
        assertEquals(small, cm.condenseToolResult(small));
    }

    @Test
    void foldDisabled_usesHeadTailTrim_andNeverCallsModel() throws Exception {
        FakeLlama llama = new FakeLlama();
        llama.fail = true; // would throw if called -> proves the model is not used
        ContextManager cm = manager(llama, false);
        String big = "HEAD" + repeat('x', 1000) + "TAIL";
        String out = cm.condenseToolResult(big);
        assertEquals(0, llama.calls);
        assertTrue(out.contains("trimmed to save context"));
        assertTrue(out.startsWith("HEAD"));
        assertTrue(out.endsWith("TAIL"));
        assertTrue(out.length() < big.length());
    }

    @Test
    void foldEnabled_summarizesEveryChunk_andShrinks() throws Exception {
        FakeLlama llama = new FakeLlama();
        ContextManager cm = manager(llama, true);
        String big = repeat('a', 1000);            // 1000 chars, threshold 200, chunk 100 -> 10 chunks
        String out = cm.condenseToolResult(big);
        assertEquals(10, llama.calls);             // every region read once
        assertTrue(out.startsWith("[folded summary"));
        assertTrue(out.length() < big.length());
        assertFalse(out.contains("trimmed to save context")); // folded, not head+tail-truncated
    }

    @Test
    void foldFailure_degradesToHeadTail() throws Exception {
        FakeLlama llama = new FakeLlama();
        llama.fail = true;                         // summary model errors out
        ContextManager cm = manager(llama, true);
        String big = repeat('z', 1000);
        String out = cm.condenseToolResult(big);
        assertTrue(out.contains("trimmed to save context")); // fell back, no exception thrown
    }

    @Test
    void tracedVariant_reportsFoldAndSizes() throws Exception {
        ContextManager cm = manager(new FakeLlama(), true);
        ContextManager.Condensed c = cm.condenseToolResultTraced(repeat('a', 1000));
        assertTrue(c.folded(), "should report a fold");
        assertEquals(1000, c.originalChars());
        assertEquals(c.text().length(), c.resultChars());
        assertTrue(c.resultChars() < c.originalChars());

        // a head+tail trim (fold disabled) is NOT reported as a fold
        ContextManager cm2 = manager(new FakeLlama(), false);
        ContextManager.Condensed t = cm2.condenseToolResultTraced(repeat('b', 1000));
        assertFalse(t.folded());

        // a small result is unchanged and not a fold
        ContextManager.Condensed s = cm.condenseToolResultTraced("tiny");
        assertFalse(s.folded());
        assertEquals("tiny", s.text());
    }
}
