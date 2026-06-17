package com.example.imini;

import java.util.List;
import java.util.Locale;

/**
 * Pure rules for per-session settings: which keys are allowed, how their values are normalized, and how a
 * session's stored default is layered under an explicit per-request value. Kept dependency-free so the
 * precedence (request value &gt; session default &gt; global default) and validation are deterministic and
 * unit-testable; the durable store is {@link SessionSettings}.
 */
public final class SessionSettingsResolver {

    private SessionSettingsResolver() {}

    /** Settings a session may persist. Currently the default permission mode. */
    public static final List<String> KEYS = List.of("mode");

    public static final List<String> MODES = List.of("ask", "auto", "plan");

    public static boolean isValidKey(String key) {
        return key != null && KEYS.contains(key.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isValidMode(String mode) {
        return mode != null && MODES.contains(mode.trim().toLowerCase(Locale.ROOT));
    }

    /** Normalize a setting value for a key (lower-cases mode; trims). Returns null if invalid for the key. */
    public static String normalizeValue(String key, String value) {
        if (!isValidKey(key) || value == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        String v = value.trim();
        if (k.equals("mode")) return isValidMode(v) ? v.toLowerCase(Locale.ROOT) : null;
        return v;
    }

    /**
     * Resolve the effective mode for a turn. An explicit, non-blank {@code requestMode} always wins; then
     * a valid {@code sessionMode} default; otherwise {@code globalDefault}. The result is always a valid
     * mode string.
     */
    public static String resolveMode(String requestMode, String sessionMode, String globalDefault) {
        if (requestMode != null && !requestMode.isBlank() && isValidMode(requestMode)) {
            return requestMode.trim().toLowerCase(Locale.ROOT);
        }
        if (sessionMode != null && isValidMode(sessionMode)) {
            return sessionMode.trim().toLowerCase(Locale.ROOT);
        }
        return isValidMode(globalDefault) ? globalDefault.trim().toLowerCase(Locale.ROOT) : "ask";
    }
}
