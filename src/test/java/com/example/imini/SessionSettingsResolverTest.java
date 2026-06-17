package com.example.imini;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure per-session settings: key/value validation and mode precedence. */
class SessionSettingsResolverTest {

    @Test
    void resolveModePrefersRequestThenSessionThenGlobal() {
        assertEquals("auto", SessionSettingsResolver.resolveMode("auto", "plan", "ask")); // request wins
        assertEquals("plan", SessionSettingsResolver.resolveMode(null, "plan", "ask"));   // session default
        assertEquals("auto", SessionSettingsResolver.resolveMode("  ", "auto", "ask"));   // blank request -> session
        assertEquals("ask", SessionSettingsResolver.resolveMode(null, null, "ask"));      // global default
    }

    @Test
    void resolveModeIgnoresInvalidValuesAndIsCaseInsensitive() {
        assertEquals("plan", SessionSettingsResolver.resolveMode("bogus", "plan", "ask")); // invalid req -> session
        assertEquals("ask", SessionSettingsResolver.resolveMode("x", "y", "z"));           // all invalid -> ask
        assertEquals("auto", SessionSettingsResolver.resolveMode("AUTO", null, "ask"));    // case-insensitive
    }

    @Test
    void keyAndModeValidation() {
        assertTrue(SessionSettingsResolver.isValidKey("mode"));
        assertTrue(SessionSettingsResolver.isValidKey("MODE"));
        assertFalse(SessionSettingsResolver.isValidKey("foo"));
        assertFalse(SessionSettingsResolver.isValidKey(null));
        assertTrue(SessionSettingsResolver.isValidMode("plan"));
        assertFalse(SessionSettingsResolver.isValidMode("zzz"));
    }

    @Test
    void normalizeValueTrimsLowercasesAndRejectsInvalid() {
        assertEquals("plan", SessionSettingsResolver.normalizeValue("mode", " PLAN "));
        assertNull(SessionSettingsResolver.normalizeValue("mode", "bad"));
        assertNull(SessionSettingsResolver.normalizeValue("foo", "x"));   // unknown key
        assertNull(SessionSettingsResolver.normalizeValue("mode", null));
    }

    @Test
    void modeSourceExplainsWhereTheModeCameFrom() {
        assertEquals("explicit", SessionSettingsResolver.modeSource("auto", "plan"));
        assertEquals("session", SessionSettingsResolver.modeSource(null, "plan"));
        assertEquals("session", SessionSettingsResolver.modeSource("  ", "auto"));
        assertEquals("global", SessionSettingsResolver.modeSource(null, null));
        assertEquals("global", SessionSettingsResolver.modeSource("bogus", null)); // invalid request, no session
    }
}
