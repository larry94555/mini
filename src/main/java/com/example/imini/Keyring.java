package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure model for a verifier keyring: trusted Ed25519 public keys, each with a short key id and an optional
 * expiry, so a verifier can trust several publishers (a small web of trust) and let that trust change over
 * time. Parsing, key-id derivation, expiry, and revocation are dependency-free and unit-testable; the
 * signature check delegates to {@link BundleSignature}. A signed artifact may name the signer's
 * {@code keyId} to pick the right key fast; verification falls back to trying every trusted key.
 */
public final class Keyring {

    /** One trusted key: its id, base64 (X.509) public key, and expiry epoch-ms (0 = never expires). */
    public record Key(String keyId, String publicKey, long expiryEpochMs) {}

    private final List<Key> keys;

    private Keyring(List<Key> keys) {
        this.keys = keys;
    }

    public List<Key> keys() {
        return keys;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** Short, stable id for a public key: first 16 hex chars of its SHA-256. */
    public static String keyIdFor(String publicKeyBase64) {
        if (publicKeyBase64 == null || publicKeyBase64.isBlank()) return "";
        String h = PluginPack.sha256(publicKeyBase64.trim());
        return h.length() >= 16 ? h.substring(0, 16) : h;
    }

    public static boolean isExpired(Key k, long now) {
        return k.expiryEpochMs() > 0 && now > k.expiryEpochMs();
    }

    /**
     * Build a keyring from a comma/newline-separated spec. Each entry is {@code keyId:base64} or bare
     * {@code base64} (id derived), with an optional {@code @<expiryEpochMs>} suffix on the key. Blank
     * entries and duplicates (by id) are dropped; order is preserved.
     */
    public static Keyring parse(String spec, String legacySingleKey) {
        Map<String, Key> byId = new LinkedHashMap<>();
        addEntry(byId, legacySingleKey);
        if (spec != null) {
            for (String raw : spec.split("[,\\n]")) addEntry(byId, raw);
        }
        return new Keyring(new ArrayList<>(byId.values()));
    }

    private static void addEntry(Map<String, Key> byId, String raw) {
        if (raw == null) return;
        String e = raw.trim();
        if (e.isEmpty()) return;
        String id, key;
        int colon = e.indexOf(':');
        // a base64 key has no ':'; "id:base64" splits on the first ':'
        if (colon > 0 && colon < e.length() - 1 && !e.substring(0, colon).contains("/")
                && !e.substring(0, colon).contains("+")) {
            id = e.substring(0, colon).trim();
            key = e.substring(colon + 1).trim();
        } else {
            key = e;
            id = null; // derive after stripping expiry
        }
        long expiry = 0;
        int at = key.lastIndexOf('@');           // optional "@<epochMillis>" expiry suffix
        if (at > 0) {
            try {
                expiry = Long.parseLong(key.substring(at + 1).trim());
                key = key.substring(0, at).trim();
            } catch (NumberFormatException ignore) { /* not an expiry suffix; leave key as-is */ }
        }
        if (id == null) id = keyIdFor(key);
        if (key.isEmpty() || id.isEmpty()) return;
        byId.putIfAbsent(id, new Key(id, key, expiry));
    }

    /** Convenience: verify with no revocations, at the current time. */
    public String verify(String payload, String signatureBase64, String preferredKeyId) {
        return verify(payload, signatureBase64, preferredKeyId, Set.of(), System.currentTimeMillis());
    }

    /**
     * Verify {@code signature} of {@code payload} against trusted keys -- skipping any whose id is in
     * {@code revoked} or that has expired at {@code now} -- trying {@code preferredKeyId} first, then every
     * key. Returns the matching trusted key id, or null.
     */
    public String verify(String payload, String signatureBase64, String preferredKeyId,
                         Set<String> revoked, long now) {
        if (signatureBase64 == null || signatureBase64.isBlank()) return null;
        Set<String> rev = revoked == null ? Set.of() : revoked;
        if (preferredKeyId != null && !preferredKeyId.isBlank()) {
            for (Key k : keys) {
                if (k.keyId().equalsIgnoreCase(preferredKeyId.trim()) && trusted(k, rev, now)
                        && BundleSignature.verifyEd25519(payload, k.publicKey(), signatureBase64)) {
                    return k.keyId();
                }
            }
        }
        for (Key k : keys) {
            if (trusted(k, rev, now) && BundleSignature.verifyEd25519(payload, k.publicKey(), signatureBase64)) {
                return k.keyId();
            }
        }
        return null;
    }

    /**
     * The id of ANY key in the ring whose signature matches, ignoring revocation/expiry -- so a caller can
     * distinguish "revoked"/"expired" from "invalid". Returns null if no key matches at all.
     */
    public String matchIgnoringStatus(String payload, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.isBlank()) return null;
        for (Key k : keys) {
            if (BundleSignature.verifyEd25519(payload, k.publicKey(), signatureBase64)) return k.keyId();
        }
        return null;
    }

    private static boolean trusted(Key k, Set<String> revoked, long now) {
        return !revoked.contains(k.keyId().toLowerCase(Locale.ROOT))
                && !revoked.contains(k.keyId())
                && !isExpired(k, now);
    }
}
