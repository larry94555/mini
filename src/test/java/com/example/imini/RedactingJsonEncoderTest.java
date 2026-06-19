package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactingJsonEncoderTest {

    @Test
    void scrubsMessageInJsonLine() {
        String json = RedactingJsonEncoder.toJson(1700000000000L, "INFO", "com.example.X", "main",
                Map.of(), "calling with api_key=supersecret and alice@example.com");
        assertTrue(json.contains("\"message\":\"calling with api_key=**** and ****@****\""));
        assertFalse(json.contains("supersecret"));
        assertFalse(json.contains("alice@example.com"));
    }

    @Test
    void emitsCoreFieldsAndEscapes() {
        String json = RedactingJsonEncoder.toJson(42L, "WARN", "log\"ger", "t1", Map.of(),
                "line\nbreak");
        assertTrue(json.startsWith("{\"ts\":\"42\""));
        assertTrue(json.contains("\"level\":\"WARN\""));
        assertTrue(json.contains("\"logger\":\"log\\\"ger\""));   // quote escaped
        assertTrue(json.contains("\"message\":\"line\\nbreak\"")); // newline escaped
        assertTrue(json.endsWith("}"));
    }

    @Test
    void includesMdcSortedAndEmptyMessageSafe() {
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("session", "s1");
        mdc.put("reqId", "abc");
        String json = RedactingJsonEncoder.toJson(1L, "INFO", "L", "t", mdc, "");
        // sorted: reqId before session
        assertTrue(json.indexOf("\"mdc.reqId\"") < json.indexOf("\"mdc.session\""));
        assertTrue(json.contains("\"message\":\"\""));
    }

    @Test
    void escHandlesControlChars() {
        assertEquals("a\\u0001b", RedactingJsonEncoder.esc("a\u0001b"));
    }
}
