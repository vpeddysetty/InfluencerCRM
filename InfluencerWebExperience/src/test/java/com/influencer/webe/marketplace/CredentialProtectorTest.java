package com.influencer.webe.marketplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envelope encryption of marketplace credentials (roadmap M3.1).
 *
 * <p>These tests encode the decisions, not only the mechanics: that an unconfigured key refuses a
 * real connection rather than quietly writing plaintext, that legacy rows stay readable, and that a
 * tampered blob fails instead of decrypting to something else.
 */
class CredentialProtectorTest {

    private static final String KEY_A = base64Key((byte) 0x11);
    private static final String KEY_B = base64Key((byte) 0x22);

    private static final String SECRET = "{\"apiKey\":\"shpat_realtoken\",\"shop\":\"acme\"}";

    private static String base64Key(byte fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static CredentialProtector withKey(String key) {
        return new CredentialProtector(key, "test-key");
    }

    // ---- round trip ----------------------------------------------------

    @Test
    @DisplayName("credentials survive an encrypt/decrypt round trip")
    void roundTrips() {
        CredentialProtector protector = withKey(KEY_A);

        String stored = protector.protect(SECRET, true);
        assertEquals(SECRET, protector.reveal(stored, "row-1"));
    }

    @Test
    @DisplayName("the stored blob contains no fragment of the plaintext")
    void storedBlobLeaksNothing() {
        String stored = withKey(KEY_A).protect(SECRET, true);

        // The whole point of the column. Asserted on the token itself rather than the full JSON,
        // because a substring check on the full string would pass even if the payload leaked in
        // pieces.
        assertFalse(stored.contains("shpat_realtoken"), "the access token appears in the blob");
        assertFalse(stored.contains("acme"), "the shop name appears in the blob");
        assertFalse(stored.contains("apiKey"), "the credential key names appear in the blob");
    }

    @Test
    @DisplayName("encrypting the same value twice produces different blobs")
    void isNotDeterministic() {
        CredentialProtector protector = withKey(KEY_A);

        // A fresh IV per call. If these matched, equal ciphertexts would reveal that two brands
        // connected the same store — and under GCM a repeated nonce is a break, not a weakening.
        assertNotEquals(protector.protect(SECRET, true), protector.protect(SECRET, true));
    }

    // ---- the fail-closed decision --------------------------------------

    @Test
    @DisplayName("real credentials are refused rather than stored unencrypted when no key is set")
    void refusesRealCredentialsWithoutAKey() {
        CredentialProtector unconfigured = withKey("");

        assertFalse(unconfigured.isConfigured());
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> unconfigured.protect(SECRET, true));
        // The message has to name the setting; "encryption not configured" sends nobody anywhere.
        assertTrue(thrown.getMessage().contains("credential-key"), thrown.getMessage());
    }

    @Test
    @DisplayName("mock credentials pass through when no key is set")
    void allowsMockCredentialsWithoutAKey() {
        // Otherwise every developer and CI run needs key material to exercise a fake store, which
        // is how a shared dummy key ends up committed.
        assertEquals(SECRET, withKey("").protect(SECRET, false));
    }

    @Test
    @DisplayName("a key too short to be AES-256 is treated as absent, not stretched")
    void rejectsAShortKey() {
        CredentialProtector protector = withKey(Base64.getEncoder().encodeToString(new byte[16]));

        // Padding or hashing a 16-byte key up to 32 would encrypt successfully while carrying half
        // the entropy the algorithm name implies, and nobody would be told.
        assertFalse(protector.isConfigured());
        assertThrows(IllegalStateException.class, () -> protector.protect(SECRET, true));
    }

    @Test
    @DisplayName("real credentials are still encrypted when a key IS configured")
    void encryptsWhenConfigured() {
        String stored = withKey(KEY_A).protect(SECRET, true);
        assertTrue(CredentialCipher.isEnvelope(stored));
    }

    // ---- tamper and wrong-key behaviour --------------------------------

    @Test
    @DisplayName("a blob altered in the database fails to decrypt instead of yielding other data")
    void detectsTampering() {
        CredentialProtector protector = withKey(KEY_A);
        String stored = protector.protect(SECRET, true);

        // Flip one character of the ciphertext segment. Under an unauthenticated mode this would
        // decrypt to corrupted-but-usable bytes; GCM's tag is what makes it an error.
        int lastColon = stored.lastIndexOf(':');
        char victim = stored.charAt(lastColon + 1);
        String tampered = stored.substring(0, lastColon + 1)
                + (victim == 'A' ? 'B' : 'A')
                + stored.substring(lastColon + 2);

        assertThrows(IllegalStateException.class, () -> protector.reveal(tampered, "row-1"));
    }

    @Test
    @DisplayName("a blob written under a different key cannot be read")
    void rejectsTheWrongKey() {
        String stored = withKey(KEY_A).protect(SECRET, true);
        assertThrows(IllegalStateException.class, () -> withKey(KEY_B).reveal(stored, "row-1"));
    }

    @Test
    @DisplayName("an encrypted blob with no key configured fails loudly, not silently empty")
    void refusesToReadEncryptedWithoutAKey() {
        String stored = withKey(KEY_A).protect(SECRET, true);

        // Returning null/empty here would reach the adapter as "this store has no credentials" and
        // surface as a vendor auth error, sending the operator to debug the wrong system.
        assertThrows(IllegalStateException.class, () -> withKey("").reveal(stored, "row-1"));
    }

    // ---- legacy rows ---------------------------------------------------

    @Test
    @DisplayName("rows written before encryption are still readable")
    void readsLegacyPlaintext() {
        // Deploying this change must not break every connection that already exists. There is no
        // migration that helps: those rows were never encrypted.
        assertEquals(SECRET, withKey(KEY_A).reveal(SECRET, "legacy-row"));
    }

    @Test
    @DisplayName("legacy plaintext is readable even before a key is configured")
    void readsLegacyPlaintextWithoutAKey() {
        assertEquals(SECRET, withKey("").reveal(SECRET, "legacy-row"));
    }

    @Test
    @DisplayName("null and blank stored values pass through untouched")
    void toleratesEmptyValues() {
        CredentialProtector protector = withKey(KEY_A);

        assertEquals(null, protector.reveal(null, "row-1"));
        assertEquals("", protector.reveal("", "row-1"));
        assertEquals(null, protector.protect(null, true));
    }

    // ---- rotation support ----------------------------------------------

    @Test
    @DisplayName("the key id is recorded in the blob so a rotation can find unrewritten rows")
    void recordsTheKeyId() {
        String stored = new CredentialProtector(KEY_A, "kek-2026-08").protect(SECRET, true);

        assertEquals("kek-2026-08", CredentialCipher.keyIdOf(stored));
    }

    @Test
    @DisplayName("a raw 32-character key is accepted even though it is also valid base64")
    void acceptsRawKeyMaterial() {
        // The formats overlap. This string is what someone types when told "a 32-character key",
        // and it is *also* syntactically valid base64 that decodes to 24 bytes. Deciding on syntax
        // would reject it as too short while the operator counts 32 characters — so length decides.
        String hexish = "0123456789abcdef0123456789abcdef";
        assertEquals(32, hexish.length());
        assertEquals(24, Base64.getDecoder().decode(hexish).length, "premise of this test");

        CredentialProtector protector = new CredentialProtector(hexish, "raw");

        assertTrue(protector.isConfigured());
        assertEquals(SECRET, protector.reveal(protector.protect(SECRET, true), "row-1"));
    }

    @Test
    @DisplayName("a genuine base64 key is read as base64, not as its 44 raw characters")
    void prefersBase64WhenItFits() {
        // KEY_A is 44 characters of base64 decoding to 32 bytes. Read raw it would be 44 bytes and
        // rejected, so this pins that base64 is tried first.
        assertEquals(44, KEY_A.length());
        assertTrue(withKey(KEY_A).isConfigured());
    }
}
