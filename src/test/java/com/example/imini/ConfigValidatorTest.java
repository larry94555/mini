package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {
    private static boolean any(List<String> l, String needle) {
        return l.stream().anyMatch(s -> s.contains(needle));
    }

    @Test
    void goodConfigWithAuthHasNoFatals() {
        List<String> issues = ConfigValidator.validate(2, 400, true, ".imini/imini.db", 500, true, 3);
        assertFalse(issues.stream().anyMatch(s -> s.startsWith(ConfigValidator.FATAL)));
    }

    @Test
    void negativeRetryIsFatal() {
        List<String> issues = ConfigValidator.validate(-1, 400, true, "db", 500, true, 1);
        assertTrue(any(issues, ConfigValidator.FATAL + "llama.max-retries"));
    }

    @Test
    void persistenceEnabledWithBlankPathIsFatal() {
        List<String> issues = ConfigValidator.validate(2, 400, true, "  ", 500, true, 1);
        assertTrue(any(issues, ConfigValidator.FATAL + "persistence.enabled=true but persistence.db-path"));
    }

    @Test
    void authEnabledWithoutPrincipalsWarns() {
        // persistenceEnabled=true, authEnabled=true, principalCount=0 → should warn about no principals
        List<String> issues = ConfigValidator.validate(2, 400, true, "db", 500, true, 0);
        assertTrue(any(issues, "no principals"));
    }

    @Test
    void authDisabledWarns() {
        List<String> issues = ConfigValidator.validate(2, 400, true, "db", 500, false, 0);
        assertTrue(any(issues, "unauthenticated"));
    }

    @Test
    void countsPrincipals() {
        assertEquals(0, ConfigValidator.countPrincipals(""));
        assertEquals(0, ConfigValidator.countPrincipals(null));
        assertEquals(2, ConfigValidator.countPrincipals("a:k1:admin,b:k2:user"));
    }
}
