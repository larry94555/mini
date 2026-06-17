package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure HMAC-SHA256 bundle signing/verification. */
class BundleSignatureTest {

    @Test
    void signProducesHexAndVerifies() {
        String sig = BundleSignature.sign("payload-abc", "s3cret");
        assertEquals(64, sig.length());                 // SHA-256 = 32 bytes = 64 hex
        assertTrue(sig.matches("[0-9a-f]{64}"));
        assertTrue(BundleSignature.verify("payload-abc", "s3cret", sig));
        assertTrue(BundleSignature.verify("payload-abc", "s3cret", sig.toUpperCase())); // case-insensitive
    }

    @Test
    void verifyRejectsWrongSecretOrTamperedPayloadOrBadSig() {
        String sig = BundleSignature.sign("payload-abc", "s3cret");
        assertFalse(BundleSignature.verify("payload-abc", "other", sig));
        assertFalse(BundleSignature.verify("payload-xyz", "s3cret", sig));
        assertFalse(BundleSignature.verify("payload-abc", "s3cret", "deadbeef"));
        assertFalse(BundleSignature.verify("payload-abc", "s3cret", null));
    }

    @Test
    void blankSecretDisablesSigning() {
        assertEquals("", BundleSignature.sign("p", ""));
        assertEquals("", BundleSignature.sign("p", null));
        assertFalse(BundleSignature.verify("p", "", "anything")); // nothing to verify against
    }

    @Test
    void deterministic() {
        assertEquals(BundleSignature.sign("x", "k"), BundleSignature.sign("x", "k"));
    }

    @Test
    void ed25519KeygenSignVerify() {
        Map<String, String> kp = BundleSignature.generateKeyPair();
        assertEquals("ed25519", kp.get("alg"));
        String pub = kp.get("publicKey"), priv = kp.get("privateKey");
        assertTrue(pub != null && priv != null);
        String sig = BundleSignature.signEd25519("payload-abc", priv);
        assertTrue(!sig.isEmpty());
        assertTrue(BundleSignature.verifyEd25519("payload-abc", pub, sig));
    }

    @Test
    void ed25519RejectsTamperWrongKeyAndBlanks() {
        Map<String, String> kp = BundleSignature.generateKeyPair();
        String pub = kp.get("publicKey"), priv = kp.get("privateKey");
        String sig = BundleSignature.signEd25519("payload-abc", priv);
        assertFalse(BundleSignature.verifyEd25519("payload-XYZ", pub, sig));       // tampered payload
        assertFalse(BundleSignature.verifyEd25519("payload-abc", BundleSignature.generateKeyPair().get("publicKey"), sig)); // wrong key
        assertEquals("", BundleSignature.signEd25519("p", ""));                     // no key
        assertFalse(BundleSignature.verifyEd25519("p", pub, ""));                   // no signature
    }
}
