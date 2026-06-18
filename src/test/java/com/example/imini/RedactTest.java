package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedactTest {
    @Test
    void masksKeepingEnds() {
        assertEquals("", Redact.mask(""));
        assertEquals("", Redact.mask(null));
        assertEquals("****", Redact.mask("abcd"));
        assertEquals("se***et", Redact.mask("secret"));
    }

    @Test
    void scrubRemovesSecrets() {
        String line = "connecting with key=supersecret123 and token=abcdef";
        String scrubbed = Redact.scrub(line, List.of("supersecret123", "abcdef"));
        assertFalse(scrubbed.contains("supersecret123"));
        assertFalse(scrubbed.contains("abcdef"));
        assertEquals("connecting with key=**** and token=****", scrubbed);
    }
}
