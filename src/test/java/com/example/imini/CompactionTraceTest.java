package com.example.imini;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compaction emits a [compact:&lt;label&gt;] trace event to the sink when it folds history into memory. */
class CompactionTraceTest {

    static class FakeLlama extends LlamaClient {
        FakeLlama() { super(null, null); }
        @Override public int countTokens(String t) { return t == null ? 0 : t.length() / 4; }
        @Override public Map<String, Object> summaryChat(List<Map<String, Object>> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", "notes");
            return r;
        }
    }

    static class CapturingSink implements RunSink {
        final List<String> lines = new ArrayList<>();
        @Override public void token(String text) {}
        @Override public void log(String line) { lines.add(line); }
    }

    private static void set(ContextManager cm, String f, Object v) throws Exception {
        Field fl = ContextManager.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(cm, v);
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    void emitsCompactTraceEvent() throws Exception {
        ContextManager cm = new ContextManager(new FakeLlama(), null);
        set(cm, "threshold", 10);     // tiny, so we compact
        set(cm, "keepRecent", 2);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "you are a helper"));
        for (int i = 0; i < 8; i++) {
            messages.add(msg("user", "message number " + i + " with some content to count"));
            messages.add(msg("assistant", "reply number " + i + " also with some content here"));
        }

        CapturingSink sink = new CapturingSink();
        List<Map<String, Object>> out = cm.compactIfNeeded(messages, "main", sink);

        assertTrue(out.size() < messages.size(), "history should be compacted");
        assertTrue(sink.lines.stream().anyMatch(l -> l.startsWith("[compact:main]")),
                "should emit a [compact:main] trace event; got " + sink.lines);
    }

    @Test
    void noEventWhenUnderThreshold() throws Exception {
        ContextManager cm = new ContextManager(new FakeLlama(), null);
        set(cm, "threshold", 100000);
        set(cm, "keepRecent", 2);
        CapturingSink sink = new CapturingSink();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "s"));
        messages.add(msg("user", "hi"));
        cm.compactIfNeeded(messages, "main", sink);
        assertEquals(0, sink.lines.size());
    }
}
