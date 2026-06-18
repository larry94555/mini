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
}
