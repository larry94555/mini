package com.example.imini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central signing/verification for bundles and plugin packs. Holds the configured signing material and
 * provides one place to sign a digest and to verify a signature -- so workspace bundles and plugin packs
 * share identical behavior. Supports public-key (Ed25519, preferred) and shared-secret (HMAC) schemes,
 * and a verifier {@link Keyring} of several trusted public keys.
 */
@Component
public class SigningService {

    @Value("${bundle.signing-secret:}") private String signingSecret;
    @Value("${bundle.signing-private-key:}") private String signingPrivateKey;
    @Value("${bundle.signing-public-key:}") private String signingPublicKey;
    @Value("${bundle.verify-public-keys:}") private String verifyPublicKeys;
    @Value("${bundle.signing-key-id:}") private String signingKeyId;
    @Value("${bundle.revoked-key-ids:}") private String revokedKeyIds;

    private String secret() { return signingSecret == null ? "" : signingSecret.trim(); }
    private String privKey() { return signingPrivateKey == null ? "" : signingPrivateKey.trim(); }
    private String pubKey() { return signingPublicKey == null ? "" : signingPublicKey.trim(); }

    /** The verifier keyring: the configured ring plus any legacy single public key. */
    public Keyring keyring() {
        return Keyring.parse(verifyPublicKeys, pubKey());
    }

    /** Revoked key ids (case-insensitive); such keys are rejected even if a signature matches them. */
    public java.util.Set<String> revoked() {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (revokedKeyIds != null) {
            for (String r : revokedKeyIds.split("[,\\n]")) {
                String t = r.trim();
                if (!t.isEmpty()) { out.add(t); out.add(t.toLowerCase(java.util.Locale.ROOT)); }
            }
        }
        return out;
    }

    /** This signer's key id: configured value, else derived from the configured public key, else "". */
    public String signerKeyId() {
        if (signingKeyId != null && !signingKeyId.isBlank()) return signingKeyId.trim();
        return pubKey().isEmpty() ? "" : Keyring.keyIdFor(pubKey());
    }

    public boolean canSign() {
        return !privKey().isEmpty() || !secret().isEmpty();
    }

    /**
     * Sign {@code sha} (a content digest) with the preferred configured scheme. Returns the fields to embed
     * (signatureAlg, signature, packSha256[, keyId]), or an empty map if signing is not configured.
     */
    public Map<String, Object> signFields(String sha) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!privKey().isEmpty()) {
            String sig = BundleSignature.signEd25519(sha, privKey());
            if (!sig.isEmpty()) {
                out.put("packSha256", sha);
                out.put("signatureAlg", BundleSignature.ALG_ED25519);
                out.put("signature", sig);
                String kid = signerKeyId();
                if (!kid.isEmpty()) out.put("keyId", kid);
            }
        } else if (!secret().isEmpty()) {
            out.put("packSha256", sha);
            out.put("signatureAlg", BundleSignature.ALG_HMAC);
            out.put("signature", BundleSignature.sign(sha, secret()));
        }
        return out;
    }

    /**
     * Verify a signature over {@code sha}. {@code alg} names the scheme ("ed25519" or "hmac-sha256";
     * default HMAC). Returns one of: "verified", "invalid", "unsigned" (no signature), "no-key" (nothing
     * configured to check the bundle's scheme).
     */
    public String verify(String sha, String alg, String signature, String keyId) {
        boolean ed = BundleSignature.ALG_ED25519.equalsIgnoreCase(alg == null ? BundleSignature.ALG_HMAC : alg);
        if (ed) {
            Keyring ring = keyring();
            if (ring.isEmpty()) return "no-key";
            if (signature == null) return "unsigned";
            long now = System.currentTimeMillis();
            if (ring.verify(sha, signature, keyId, revoked(), now) != null) return "verified";
            // a signature that matches a key the ring no longer trusts -> say why
            String matched = ring.matchIgnoringStatus(sha, signature);
            if (matched != null) {
                if (revoked().contains(matched) || revoked().contains(matched.toLowerCase(java.util.Locale.ROOT))) return "revoked";
                return "expired";
            }
            return "invalid";
        } else {
            if (secret().isEmpty()) return "no-key";
            if (signature == null) return "unsigned";
            return BundleSignature.verify(sha, secret(), signature) ? "verified" : "invalid";
        }
    }

    public Map<String, String> generateKeyPair() {
        return BundleSignature.generateKeyPair();
    }
}
