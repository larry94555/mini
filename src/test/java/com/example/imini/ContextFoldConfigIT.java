package com.example.imini;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-style check that the SHIPPED fold defaults (from application.properties) actually fold a
 * realistic, very large input -- not just the tiny hand-set thresholds used by the unit test. Uses a
 * deterministic fake summary model so it runs without a live llama-server. For a genuine end-to-end fold
 * against a running model server, see the manual procedure in TESTING.md.
 */
class ContextFoldConfigIT {

    static class FakeLlama extends LlamaClient {
        int calls = 0;
        FakeLlama() { super(null); }
        @Override public int countTokens(String t) { return t == null ? 0 : t.length() / 4; }
        @Override public Map<String, Object> summaryChat(List<Map<String, Object>> m) {
            calls++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", "SUM");
            return r;
        }
    }

    private static int prop(Properties p, String k, int d) {
        String v = p.getProperty(k);
        return v == null || v.isBlank() ? d : Integer.parseInt(v.trim());
    }

    private static void set(ContextManager cm, String f, Object v) throws Exception {
        Field fl = ContextManager.class.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(cm, v);
    }

    @Test
    void shippedDefaultsFoldAHugeInput() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties should be on the test classpath");
            p.load(in);
        }
        FakeLlama llama = new FakeLlama();
        ContextManager cm = new ContextManager(llama, null);
        set(cm, "maxToolChars", prop(p, "agent.max-tool-result-chars", 4000));
        set(cm, "foldEnabled", Boolean.parseBoolean(p.getProperty("agent.fold-enabled", "true")));
        set(cm, "foldThresholdChars", prop(p, "agent.fold-threshold-chars", 24000));
        set(cm, "foldChunkChars", prop(p, "agent.fold-chunk-chars", 8000));
        set(cm, "foldTargetChars", prop(p, "agent.fold-target-chars", 4000));
        set(cm, "foldMaxDepth", prop(p, "agent.fold-max-depth", 2));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append('a');   // ~100 KB single input
        String out = cm.condenseToolResult(sb.toString());

        assertTrue(out.startsWith("[folded summary"), "should fold under the shipped defaults");
        assertTrue(out.length() < 100_000, "digest should be far smaller than the input");
        assertTrue(llama.calls >= 12, "every ~8KB chunk should be summarized; calls=" + llama.calls);
    }
}
