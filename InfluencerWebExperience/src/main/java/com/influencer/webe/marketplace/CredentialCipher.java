package com.influencer.webe.marketplace;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption for marketplace credentials (roadmap M3.1).
 *
 * <p><b>Why envelope and not "just encrypt it".</b> A single key encrypting every row cannot be
 * rotated: rotating it means decrypting and rewriting every credential in the database in one
 * transaction, which is exactly the migration nobody runs, so the key never rotates. Envelope
 * encryption gives every record its own random data key (DEK) and encrypts only that DEK with the
 * long-lived key-encryption key (KEK). Rotating the KEK rewrites a few hundred bytes per row
 * instead of the payload, and a leaked DEK exposes one connection rather than the whole table.
 *
 * <p><b>AES-256-GCM at both layers.</b> GCM is authenticated: a blob altered in the database fails
 * to decrypt rather than decrypting to something else. With CBC or CTR an attacker who can write to
 * the column can flip bits in the ciphertext and silently change the credentials the adapter then
 * uses. The authentication tag is what makes that a loud failure.
 *
 * <p><b>The blob is self-describing.</b> Format:
 * {@code v1:<keyId>:<wrappedDekB64>:<dekIvB64>:<payloadIvB64>:<ciphertextB64>}. The version prefix
 * exists so the algorithm can change later without guessing at old rows, and the key id so a row
 * encrypted under a retired KEK can still be identified and read during a rotation. Omitting either
 * is what makes a format permanent by accident.
 *
 * <p><b>A fresh IV per encryption, from {@link SecureRandom}.</b> Reusing a nonce under GCM is not
 * a weakening, it is a break — two messages under the same key and nonce leak their XOR and, worse,
 * allow the authentication key itself to be recovered. Nothing here ever derives an IV from the
 * data.
 *
 * <p>This class holds no policy about whether encryption is required — see
 * {@link CredentialProtector}, which decides that and is the only intended caller.
 */
final class CredentialCipher {

    /** Prefix identifying this blob layout. A future format bumps this rather than reusing it. */
    static final String VERSION = "v1";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int DEK_BITS = 256;
    private static final int FIELDS = 6;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private CredentialCipher() {
    }

    /**
     * Wraps {@code plaintext} under a freshly generated data key, which is itself wrapped with
     * {@code kek}.
     *
     * @param keyId identifier of the KEK, stored alongside so rotation can tell rows apart
     */
    static String encrypt(String plaintext, SecretKey kek, String keyId) {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(DEK_BITS, RANDOM);
            SecretKey dek = generator.generateKey();

            byte[] payloadIv = randomIv();
            byte[] ciphertext = transform(Cipher.ENCRYPT_MODE, dek, payloadIv,
                    plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] dekIv = randomIv();
            byte[] wrappedDek = transform(Cipher.ENCRYPT_MODE, kek, dekIv, dek.getEncoded());

            return String.join(":",
                    VERSION,
                    keyId,
                    ENCODER.encodeToString(wrappedDek),
                    ENCODER.encodeToString(dekIv),
                    ENCODER.encodeToString(payloadIv),
                    ENCODER.encodeToString(ciphertext));
        } catch (Exception e) {
            // Deliberately no plaintext in the message — this string reaches logs.
            throw new IllegalStateException("Unable to encrypt credentials", e);
        }
    }

    /**
     * Reverses {@link #encrypt}. Throws if the blob was not produced by this class, was encrypted
     * under a different key, or has been altered.
     */
    static String decrypt(String blob, SecretKey kek) {
        String[] parts = blob.split(":", FIELDS);
        if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("Not a recognised credential envelope");
        }
        try {
            byte[] wrappedDek = DECODER.decode(parts[2]);
            byte[] dekIv = DECODER.decode(parts[3]);
            byte[] payloadIv = DECODER.decode(parts[4]);
            byte[] ciphertext = DECODER.decode(parts[5]);

            byte[] rawDek = transform(Cipher.DECRYPT_MODE, kek, dekIv, wrappedDek);
            SecretKey dek = new SecretKeySpec(rawDek, "AES");

            byte[] plaintext = transform(Cipher.DECRYPT_MODE, dek, payloadIv, ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt credentials", e);
        }
    }

    /** Whether {@code value} looks like an envelope this class produced, rather than legacy JSON. */
    static boolean isEnvelope(String value) {
        return value != null && value.startsWith(VERSION + ":");
    }

    /** The key id recorded in a blob, used to spot rows still under a retired KEK. */
    static String keyIdOf(String blob) {
        String[] parts = blob.split(":", FIELDS);
        return parts.length == FIELDS && VERSION.equals(parts[0]) ? parts[1] : null;
    }

    private static byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    private static byte[] transform(int mode, SecretKey key, byte[] iv, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(input);
    }
}
