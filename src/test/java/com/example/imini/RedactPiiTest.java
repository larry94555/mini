package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactPiiTest {

    @Test
    void masksBearerTokens() {
        String out = Redact.scrubPii("Authorization: Bearer abc123.DEF-456_token");
        assertTrue(out.contains("Bearer ****"));
        assertFalse(out.contains("abc123"));
    }

    @Test
    void masksKeyedSecrets() {
        assertEquals("api_key=****", Redact.scrubPii("api_key=supersecretvalue"));
        assertTrue(Redact.scrubPii("password: hunter2hunter").contains("password=****"));
    }

    @Test
    void masksSkAwsJwt() {
        assertEquals("key sk-**** here", Redact.scrubPii("key sk-ABCDEF123456 here"));
        assertTrue(Redact.scrubPii("AKIAIOSFODNN7EXAMPLE").contains("AKIA****"));
        assertTrue(Redact.scrubPii("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9").contains("eyJ****"));
    }

    @Test
    void masksEmails() {
        assertEquals("contact ****@**** now", Redact.scrubPii("contact alice@example.com now"));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String plain = "the agent ran read_file and returned 42 lines";
        assertEquals(plain, Redact.scrubPii(plain));
    }

    @Test
    void nullAndEmptySafe() {
        assertEquals(null, Redact.scrubPii(null));
        assertEquals("", Redact.scrubPii(""));
    }
}
