package com.example.imini;

import java.util.Collection;

/**
 * Small helpers to keep secrets (API keys, signing secrets) out of logs. {@link #mask} shows just enough to
 * recognize a value without revealing it; {@link #scrub} removes known secret substrings from a string before
 * it is logged.
 */
public final class Redact {

    private Redact() {}

    /** Mask a secret: "" stays "", short secrets become "****", longer ones keep 2 head + 2 tail chars. */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) return "";
        if (secret.length() <= 4) return "****";
        return secret.substring(0, 2) + "***" + secret.substring(secret.length() - 2);
    }

    /** Replace every non-blank secret occurrence in {@code text} with "****". Null-safe. */
    public static String scrub(String text, Collection<String> secrets) {
        if (text == null || secrets == null) return text;
        String out = text;
        for (String s : secrets) {
            if (s != null && !s.isBlank()) out = out.replace(s, "****");
        }
        return out;
    }

    // --- pattern-based PII / secret scrubbing --------------------------------
    // These catch secret-shaped values that we don't have an exact copy of (so the substring scrub above
    // can't help) -- bearer tokens, common API-key shapes, JWTs, AWS keys, and email addresses. The
    // patterns are intentionally conservative: they target distinctive shapes, not ordinary prose.

    private static final java.util.regex.Pattern BEARER =
            java.util.regex.Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+");
    private static final java.util.regex.Pattern KEYED_SECRET =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(api[_-]?key|secret|token|password|passwd|pwd)\\b\\s*[:=]\\s*\"?[A-Za-z0-9._\\-]{4,}\"?");
    private static final java.util.regex.Pattern SK_KEY =
            java.util.regex.Pattern.compile("\\bsk-[A-Za-z0-9]{6,}\\b");
    private static final java.util.regex.Pattern AWS_KEY =
            java.util.regex.Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final java.util.regex.Pattern JWT =
            java.util.regex.Pattern.compile("\\beyJ[A-Za-z0-9._\\-]{10,}\\b");
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");

    /**
     * Mask secret- and PII-shaped substrings in {@code text}: bearer tokens, {@code key=value} secrets,
     * {@code sk-} / AWS / JWT tokens, and email addresses. Null-safe and idempotent. This is for keeping
     * such values out of trace attributes and log lines; it is best-effort pattern matching, not a
     * guarantee that every secret is caught.
     */
    public static String scrubPii(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        out = BEARER.matcher(out).replaceAll("Bearer ****");
        out = KEYED_SECRET.matcher(out).replaceAll(m -> m.group(1) + "=****");
        out = SK_KEY.matcher(out).replaceAll("sk-****");
        out = AWS_KEY.matcher(out).replaceAll("AKIA****");
        out = JWT.matcher(out).replaceAll("eyJ****");
        out = EMAIL.matcher(out).replaceAll("****@****");
        return out;
    }
}
