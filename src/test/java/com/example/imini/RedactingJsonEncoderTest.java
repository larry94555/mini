package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The redacting JSON encoder scrubs secrets/PII from the bytes the delegate JsonEncoder produces. */
class RedactingJsonEncoderTest {

    private static String redact(String s) {
        return new String(RedactingJsonEncoder.redact(s.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    @Test
    void scrubsSecretsInEncodedJsonLine() {
        String raw = "{\"level\":\"INFO\",\"message\":\"calling api_key=supersecret for alice@example.com\"}";
        String out = redact(raw);
        assertTrue(out.contains("api_key=****"));
        assertTrue(out.contains("****@****"));
        assertFalse(out.contains("supersecret"));
        assertFalse(out.contains("alice@example.com"));
        assertTrue(out.startsWith("{\"level\":\"INFO\""));
    }

    @Test
    void leavesCleanJsonUnchanged() {
        String raw = "{\"level\":\"INFO\",\"message\":\"ran read_file, 42 lines\"}";
        assertEquals(raw, redact(raw));
    }

    @Test
    void nullAndEmptyBytesSafe() {
        assertEquals(0, RedactingJsonEncoder.redact(new byte[0]).length);
        assertEquals(null, RedactingJsonEncoder.redact(null));
    }
}
