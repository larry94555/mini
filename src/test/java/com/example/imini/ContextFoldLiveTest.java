package com.example.imini;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end fold over real HTTP: a tiny in-process OpenAI-compatible stub server stands in for
 * llama-server, and a real {@link LlamaClient} (real JSON, real {@code HttpClient}) summarizes each chunk
 * against it. This exercises the genuine {@code summaryChat} path -- request serialization, transport, and
 * response parsing -- not a subclassed fake. Self-contained, so it runs in CI with no external model.
 */
class ContextFoldLiveTest {

    private static void set(Object o, Class<?> c, String f, Object v) throws Exception {
        Field fl = c.getDeclaredField(f);
        fl.setAccessible(true);
        fl.set(o, v);
    }

    @Test
    void foldsAHugeInputOverRealHttp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            calls.incrementAndGet();
            ex.getRequestBody().readAllBytes(); // drain request
            byte[] body = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"SUMMARY\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();

            LlamaClient llama = new LlamaClient(null, null);
            set(llama, LlamaClient.class, "summaryBaseUrl", "http://127.0.0.1:" + port);
            set(llama, LlamaClient.class, "model", "stub-model");
            set(llama, LlamaClient.class, "maxRetries", 0);

            ContextManager cm = new ContextManager(llama, null);
            set(cm, ContextManager.class, "maxToolChars", 4000);
            set(cm, ContextManager.class, "foldEnabled", true);
            set(cm, ContextManager.class, "foldThresholdChars", 24000);
            set(cm, ContextManager.class, "foldChunkChars", 8000);
            set(cm, ContextManager.class, "foldTargetChars", 4000);
            set(cm, ContextManager.class, "foldMaxDepth", 2);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 100_000; i++) sb.append('a');   // ~100 KB single input

            String out = cm.condenseToolResult(sb.toString());

            assertTrue(out.startsWith("[folded summary"), "should fold over real HTTP");
            assertTrue(out.contains("SUMMARY"), "digest should contain the model's summary text");
            assertTrue(out.length() < 100_000, "digest should be far smaller than the input");
            assertTrue(calls.get() >= 12, "summary model called per chunk over HTTP; calls=" + calls.get());
        } finally {
            server.stop(0);
        }
    }
}
