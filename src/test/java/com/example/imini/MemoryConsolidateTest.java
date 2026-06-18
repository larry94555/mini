package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The durable-memory quality guard: leave small notes alone; consolidate oversized ones via the model. */
class MemoryConsolidateTest {

    static class FakeLlama extends LlamaClient {
        boolean called = false;
        FakeLlama() { super(null, null); }
        @Override public int countTokens(String t) { return t == null ? 0 : t.length() / 4; }
        @Override public Map<String, Object> summaryChat(List<Map<String, Object>> m) {
            called = true;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", "consolidated note");
            return r;
        }
    }

    private static void set(ContextManager cm, String f, Object v) throws Exception {
        Field fl = ContextManager.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(cm, v);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    void smallNoteIsLeftUnchangedAndModelNotCalled() throws Exception {
        FakeLlama llama = new FakeLlama();
        ContextManager cm = new ContextManager(llama, null);
        set(cm, "memoryMaxChars", 4000);
        String note = "a few durable facts";
        assertEquals(note, cm.consolidateMemoryIfNeeded(note));
        assertTrue(!llama.called, "model must not be called for a small note");
    }

    @Test
    void oversizedNoteIsConsolidatedViaModel() throws Exception {
        FakeLlama llama = new FakeLlama();
        ContextManager cm = new ContextManager(llama, null);
        set(cm, "memoryMaxChars", 100);
        String out = cm.consolidateMemoryIfNeeded(repeat('x', 500));
        assertTrue(llama.called, "model should consolidate an oversized note");
        assertEquals("consolidated note", out);
        assertTrue(out.length() <= 100);
    }

    @Test
    void nullIsSafe() throws Exception {
        ContextManager cm = new ContextManager(new FakeLlama(), null);
        set(cm, "memoryMaxChars", 100);
        assertEquals(null, cm.consolidateMemoryIfNeeded(null));
    }
}
