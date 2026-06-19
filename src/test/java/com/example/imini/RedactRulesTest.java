package com.example.imini;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactRulesTest {

    @AfterEach
    void reset() {
        Redact.setExtraRules(List.of()); // don't leak rules into other tests
    }

    @Test
    void parsesRulesWithDefaultAndCustomReplacement() {
        List<Redact.Rule> rules = Redact.parseRules("EMP-\\d{6}=>EMP-****;;(?i)\\bsecretword\\b");
        assertEquals(2, rules.size());
        assertEquals("EMP-****", rules.get(0).replacement());
        assertEquals("****", rules.get(1).replacement()); // default
    }

    @Test
    void skipsInvalidRegex() {
        List<Redact.Rule> rules = Redact.parseRules("valid\\d+;;([unclosed");
        assertEquals(1, rules.size());
    }

    @Test
    void extraRulesApplyAfterBuiltins() {
        Redact.setExtraRules(Redact.parseRules("EMP-\\d{6}=>EMP-****"));
        String out = Redact.scrubPii("user EMP-123456 logged in with api_key=topsecret");
        assertTrue(out.contains("EMP-****"));
        assertFalse(out.contains("EMP-123456"));
        assertTrue(out.contains("api_key=****")); // built-in still applies
    }

    @Test
    void noExtraRulesLeavesBuiltinBehaviorUnchanged() {
        Redact.setExtraRules(List.of());
        assertEquals("hello world", Redact.scrubPii("hello world"));
    }
}
