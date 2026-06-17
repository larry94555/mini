package com.example.imini;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure signing/verification for bundles. Two schemes, both JDK-only (no dependencies):
 *
 * <ul>
 *   <li><b>HMAC-SHA256</b> (shared secret): proves the signer held the secret -- provenance among parties
 *       who share it. Symmetric: anyone who can verify can also sign.</li>
 *   <li><b>Ed25519</b> (public-key): the signer holds a private key; verifiers need only the public key.
 *       This is true third-party provenance -- a verifier cannot forge a signature.</li>
 * </ul>
 *
 * Both sit on top of the SHA-256 integrity check that already protects the bytes.
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

    public static final String ALG_HMAC = "hmac-sha256";
    public static final String ALG_ED25519 = "ed25519";

    /** Generate an Ed25519 key pair as base64 (X.509 public, PKCS#8 private). Returns {publicKey, privateKey}. */
    public static Map<String, String> generateKeyPair() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = kpg.generateKeyPair();
            out.put("alg", ALG_ED25519);
            out.put("publicKey", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
            out.put("privateKey", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        } catch (Exception e) {
            out.put("error", "keygen failed: " + e.getMessage());
        }
        return out;
    }

    /** Sign {@code payload} with a base64 PKCS#8 Ed25519 private key; base64 signature, or "" on failure. */
    public static String signEd25519(String payload, String privateKeyBase64) {
        if (payload == null || privateKeyBase64 == null || privateKeyBase64.isBlank()) return "";
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyBase64.trim());
            PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(pk);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            return "";
        }
    }

    /** Verify a base64 Ed25519 signature of {@code payload} against a base64 X.509 public key. */
    public static boolean verifyEd25519(String payload, String publicKeyBase64, String signatureBase64) {
        if (payload == null || publicKeyBase64 == null || publicKeyBase64.isBlank()
                || signatureBase64 == null || signatureBase64.isBlank()) return false;
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyBase64.trim());
            PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
