package com.example.imini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure model for a verifier keyring: a set of trusted Ed25519 public keys, each with a short key id, so a
 * verifier can trust several publishers (a small web of trust) instead of exactly one. Parsing and key-id
 * derivation are dependency-free and unit-testable; the actual signature check delegates to
 * {@link BundleSignature}. A bundle/pack may name the signer's {@code keyId} to pick the right key fast;
 * verification falls back to trying every trusted key.
 */
public final class Keyring {

    /** One trusted key: its id and base64 (X.509) public key. */
    public record Key(String keyId, String publicKey) {}

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

    /**
     * Build a keyring from a comma/newline-separated spec of entries, each {@code keyId:base64} or bare
     * {@code base64} (id derived), plus an optional legacy single key. Blank entries and duplicates (by
     * id) are dropped; order is preserved.
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
            id = keyIdFor(key);
        }
        if (key.isEmpty() || id.isEmpty()) return;
        byId.putIfAbsent(id, new Key(id, key));
    }

    /**
     * Verify {@code signature} of {@code payload} against the ring, trying {@code preferredKeyId} first
     * (if given), then every key. Returns the matching key id, or null if none verify.
     */
    public String verify(String payload, String signatureBase64, String preferredKeyId) {
        if (signatureBase64 == null || signatureBase64.isBlank()) return null;
        if (preferredKeyId != null && !preferredKeyId.isBlank()) {
            for (Key k : keys) {
                if (k.keyId().equalsIgnoreCase(preferredKeyId.trim())
                        && BundleSignature.verifyEd25519(payload, k.publicKey(), signatureBase64)) {
                    return k.keyId();
                }
            }
        }
        for (Key k : keys) {
            if (BundleSignature.verifyEd25519(payload, k.publicKey(), signatureBase64)) return k.keyId();
        }
        return null;
    }
}
