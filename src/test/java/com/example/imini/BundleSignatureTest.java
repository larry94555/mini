package com.example.imini;

import org.junit.jupiter.api.Test;

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
}
