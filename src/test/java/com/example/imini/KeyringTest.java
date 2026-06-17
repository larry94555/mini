package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure verifier-keyring: parsing, key-id derivation, and ring verification (Ed25519 via JDK runtime). */
class KeyringTest {

    @Test
    void keyIdIs16HexAndStable() {
        String pub = BundleSignature.generateKeyPair().get("publicKey");
        String id = Keyring.keyIdFor(pub);
        assertEquals(16, id.length());
        assertTrue(id.matches("[0-9a-f]{16}"));
        assertEquals(id, Keyring.keyIdFor(pub)); // stable
        assertEquals("", Keyring.keyIdFor(""));
    }

    @Test
    void parsesMixedEntriesAndLegacySingleAndDedupes() {
        String pubA = BundleSignature.generateKeyPair().get("publicKey");
        String pubB = BundleSignature.generateKeyPair().get("publicKey");
        Keyring ring = Keyring.parse(pubA + ", myid:" + pubB, null);
        assertEquals(2, ring.keys().size());
        assertTrue(ring.keys().stream().anyMatch(k -> k.keyId().equals("myid")));

        assertEquals(1, Keyring.parse(null, pubA).keys().size());          // legacy single
        assertEquals(1, Keyring.parse(pubA + "," + pubA, pubA).keys().size()); // dedupe by id
        assertTrue(Keyring.parse("", "").isEmpty());
        assertTrue(Keyring.parse(null, null).isEmpty());
    }

    @Test
    void verifyTriesPreferredThenFallsBackAndRejectsTamper() {
        Map<String, String> kp = BundleSignature.generateKeyPair();
        String pub = kp.get("publicKey"), priv = kp.get("privateKey");
        String otherPub = BundleSignature.generateKeyPair().get("publicKey");
        Keyring ring = Keyring.parse(otherPub + ", signer:" + pub, null);
        String sig = BundleSignature.signEd25519("digest-123", priv);

        assertEquals("signer", ring.verify("digest-123", sig, "signer")); // preferred id
        assertEquals("signer", ring.verify("digest-123", sig, null));     // fallback over all keys
        assertEquals("signer", ring.verify("digest-123", sig, "wrong-id")); // bad id -> still found by fallback
        assertNull(ring.verify("tampered", sig, null));
        assertNull(ring.verify("digest-123", "", null));
    }

    @Test
    void distinctKeysGetDistinctIds() {
        String a = BundleSignature.generateKeyPair().get("publicKey");
        String b = BundleSignature.generateKeyPair().get("publicKey");
        assertNotEquals(Keyring.keyIdFor(a), Keyring.keyIdFor(b));
        assertFalse(Keyring.parse(a, null).isEmpty());
    }

    @Test
    void expiredKeyNotTrustedButStillMatchable() {
        Map<String, String> kp = BundleSignature.generateKeyPair();
        String pub = kp.get("publicKey"), priv = kp.get("privateKey");
        long now = 1_000_000_000_000L;
        String sig = BundleSignature.signEd25519("digest", priv);

        Keyring expired = Keyring.parse("k1:" + pub + "@" + (now - 1000), null);
        assertTrue(Keyring.isExpired(expired.keys().get(0), now));
        assertNull(expired.verify("digest", sig, "k1", Set.of(), now));         // expired -> not trusted
        assertEquals("k1", expired.matchIgnoringStatus("digest", sig));         // but signature still matches

        Keyring future = Keyring.parse("k2:" + pub + "@" + (now + 100000), null);
        assertEquals("k2", future.verify("digest", sig, "k2", Set.of(), now));  // not yet expired

        Keyring noExpiry = Keyring.parse("k3:" + pub, null);                     // 0 = never expires
        assertEquals("k3", noExpiry.verify("digest", sig, null, Set.of(), now + 999_999_999_999L));
    }

    @Test
    void revokedKeyRejected() {
        Map<String, String> kp = BundleSignature.generateKeyPair();
        String pub = kp.get("publicKey"), priv = kp.get("privateKey");
        long now = System.currentTimeMillis();
        String sig = BundleSignature.signEd25519("digest", priv);
        Keyring ring = Keyring.parse("k1:" + pub, null);

        assertNull(ring.verify("digest", sig, "k1", Set.of("k1"), now));        // revoked
        assertEquals("k1", ring.verify("digest", sig, "k1", Set.of("other"), now)); // not revoked
        assertEquals("k1", ring.matchIgnoringStatus("digest", sig));            // match ignores revocation
    }
}
