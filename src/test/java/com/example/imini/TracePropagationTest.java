package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracePropagationTest {

    @Test
    void parsesValidTraceparent() {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        String[] ctx = Tracer.parseTraceparent(tp);
        assertNotNull(ctx);
        assertEquals("0af7651916cd43dd8448eb211c80319c", ctx[0]); // traceId
        assertEquals("b7ad6b7169203331", ctx[1]);                 // parent spanId
    }

    @Test
    void rejectsMalformedTraceparent() {
        assertNull(Tracer.parseTraceparent(null));
        assertNull(Tracer.parseTraceparent(""));
        assertNull(Tracer.parseTraceparent("garbage"));
        assertNull(Tracer.parseTraceparent("01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")); // bad version
        assertNull(Tracer.parseTraceparent("00-tooshort-b7ad6b7169203331-01"));                          // bad traceId
        assertNull(Tracer.parseTraceparent("00-0af7651916cd43dd8448eb211c80319c-short-01"));             // bad spanId
        // all-zero ids are invalid per the spec
        assertNull(Tracer.parseTraceparent("00-00000000000000000000000000000000-b7ad6b7169203331-01"));
        assertNull(Tracer.parseTraceparent("00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01"));
    }

    @Test
    void otlpJsonContainsSpanIdentityAndStatus() {
        // Build a span via reflection-free path: use a real Tracer, enable it, start + end a span.
        Tracer t = new Tracer(new Database());
        try {
            java.lang.reflect.Field f = Tracer.class.getDeclaredField("enabled");
            f.setAccessible(true); f.set(t, true);
        } catch (Exception e) { throw new RuntimeException(e); }
        Tracer.Span s = t.start("ask").attr("tenant", "alice").attr("latency_ms", 12L);
        s.end();
        String json = Tracer.otlpJson(s, "imini");
        assertTrue(json.contains("\"resourceSpans\""));
        assertTrue(json.contains("\"service.name\""));
        assertTrue(json.contains("\"traceId\":\"" + s.traceId() + "\""));
        assertTrue(json.contains("\"spanId\":\"" + s.spanId() + "\""));
        assertTrue(json.contains("\"name\":\"ask\""));
        assertTrue(json.contains("\"startTimeUnixNano\""));
        assertTrue(json.contains("\"endTimeUnixNano\""));
        assertTrue(json.contains("\"tenant\""));
        assertTrue(json.contains("\"status\":{\"code\":1}")); // OK -> 1
    }

    @Test
    void otlpJsonMapsErrorStatus() {
        Tracer t = new Tracer(new Database());
        try {
            java.lang.reflect.Field f = Tracer.class.getDeclaredField("enabled");
            f.setAccessible(true); f.set(t, true);
        } catch (Exception e) { throw new RuntimeException(e); }
        Tracer.Span s = t.start("ask").error("boom");
        s.end();
        String json = Tracer.otlpJson(s, "imini");
        assertTrue(json.contains("\"status\":{\"code\":2}")); // ERROR -> 2
        assertTrue(json.contains("\"error\""));
    }
}
