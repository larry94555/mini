package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracerTest {

    @Test
    void hexIdsHaveCorrectLengthAndAreHex() {
        String trace = Tracer.hex(16); // 16 bytes -> 32 hex chars (W3C trace id)
        String span = Tracer.hex(8);   // 8 bytes -> 16 hex chars (W3C span id)
        assertEquals(32, trace.length());
        assertEquals(16, span.length());
        assertTrue(trace.matches("[0-9a-f]{32}"));
        assertTrue(span.matches("[0-9a-f]{16}"));
        assertNotEquals(trace, Tracer.hex(16)); // random
    }

    @Test
    void attrsToJsonEscapesValues() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("a", "1");
        m.put("b", "two \"words\"");
        String json = Tracer.attrsToJson(m);
        assertEquals("{\"a\":\"1\",\"b\":\"two \\\"words\\\"\"}", json);
    }

    @Test
    void attrsToJsonEmpty() {
        assertEquals("{}", Tracer.attrsToJson(new java.util.LinkedHashMap<>()));
    }

    @Test
    void escHandlesControlChars() {
        assertEquals("a\\nb\\tc", Tracer.esc("a\nb\tc"));
    }
}
