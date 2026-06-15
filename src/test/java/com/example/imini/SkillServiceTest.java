package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure resolution of a per-session skill override over the global default. */
class SkillServiceTest {

    @Test
    void effectiveEnabledPrefersSessionOverrideThenGlobal() {
        // no override -> follow the global default
        assertTrue(SkillService.effectiveEnabled(true, null));
        assertFalse(SkillService.effectiveEnabled(false, null));
        // override wins either way
        assertTrue(SkillService.effectiveEnabled(false, Boolean.TRUE));
        assertFalse(SkillService.effectiveEnabled(true, Boolean.FALSE));
    }
}
