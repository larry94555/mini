package com.example.imini;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Pure HMAC-SHA256 signing/verification for bundles, using a shared secret. This proves a bundle was
 * produced by someone who holds the secret -- i.e. provenance among parties who share it -- on top of the
 * SHA-256 integrity check that already protects the bytes. It is symmetric (shared-secret), not
 * public-key, signing: anyone who can verify can also sign. JDK-only (javax.crypto), no dependencies.
 */
public final class BundleSignature {

    private BundleSignature() {}

    /** Lowercase-hex HMAC-SHA256 of {@code payload} under {@code secret}. Empty string if either is blank. */
    public static String sign(String payload, String secret) {
        if (payload == null || secret == null || secret.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** True iff {@code signature} is a valid HMAC of {@code payload} under {@code secret} (constant-time). */
    public static boolean verify(String payload, String secret, String signature) {
        String expected = sign(payload, secret);
        if (expected.isEmpty() || signature == null) return false;
        return constantTimeEquals(expected, signature.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
