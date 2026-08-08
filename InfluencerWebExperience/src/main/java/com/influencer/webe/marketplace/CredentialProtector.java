package com.influencer.webe.marketplace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Decides how marketplace credentials are stored, and is the only class that holds the KEK
 * (roadmap M3.1).
 *
 * <p><b>The problem being fixed.</b> Credentials were serialised to JSON and written straight into
 * a column named {@code credentials_encrypted}. A column whose name asserts a property it does not
 * have is worse than an honestly-named one: every reader downstream — a DBA granting read access, a
 * backup policy, an incident responder triaging a dump — reasonably assumes the contents are safe
 * to handle loosely. This class makes the name true.
 *
 * <h2>Three decisions worth keeping</h2>
 *
 * <p><b>1. A real credential is never written unencrypted, even if that means refusing to connect.</b>
 * The tempting design is to encrypt when a key is present and fall back to plaintext when it is
 * not, so nothing breaks in development. That fallback is precisely the incident: it is silent, it
 * looks configured, and the one deployment where the environment variable was missed is the one
 * holding real Shopify tokens. So {@link #protect} throws when no KEK is set and the provider
 * charges real credentials. Failing a connect attempt is recoverable; a leaked store token is not.
 *
 * <p><b>2. Mock credentials are exempt, and only mock ones.</b> Requiring a KEK for the mock
 * provider would mean every developer and every CI run has to configure key material to exercise a
 * fake store, which ends with a well-known dummy key committed to the repo — a key everyone shares
 * and nobody rotates. The exemption is keyed off {@link MarketplaceProvider#usesRealCredentials()},
 * declared by the adapter itself, so a new real adapter is protected by default and has to opt out
 * deliberately rather than being forgotten into plaintext.
 *
 * <p><b>3. Reads tolerate legacy plaintext; writes never produce it.</b> Rows written before this
 * change hold bare JSON. Refusing to read them would break every existing connection on deploy, and
 * a migration cannot help — the rows are only decryptable in the sense that they were never
 * encrypted. {@link #reveal} therefore accepts both shapes and logs a warning naming the row, while
 * {@link #protect} only ever emits an envelope. Existing rows re-encrypt as they are rewritten.
 */
@Component
public class CredentialProtector {

    private static final Logger log = LoggerFactory.getLogger(CredentialProtector.class);

    /** AES-256 needs exactly this many bytes of key material. */
    private static final int KEY_BYTES = 32;

    private final SecretKey kek;
    private final String keyId;

    public CredentialProtector(
            @Value("${web-experience.marketplace.credential-key:}") String encodedKey,
            @Value("${web-experience.marketplace.credential-key-id:default}") String keyId) {
        this.keyId = keyId == null || keyId.isBlank() ? "default" : keyId.trim();
        this.kek = parseKey(encodedKey);

        if (this.kek == null) {
            log.warn("No marketplace credential key is configured. Connections to providers that "
                    + "use real credentials will be refused. Set "
                    + "web-experience.marketplace.credential-key to a base64-encoded 32-byte key "
                    + "(openssl rand -base64 32).");
        }
    }

    /** Whether a KEK is available, and therefore whether real credentials can be stored. */
    public boolean isConfigured() {
        return kek != null;
    }

    /** The configured key id, recorded in each blob so a rotation can find rows to rewrite. */
    public String keyId() {
        return keyId;
    }

    /**
     * Encrypts {@code plaintext} for storage.
     *
     * @param requiresProtection whether the credentials are real and must not be stored in the
     *                           clear; see decision 2 above
     * @throws IllegalStateException when protection is required and no KEK is configured
     */
    public String protect(String plaintext, boolean requiresProtection) {
        if (plaintext == null) {
            return null;
        }
        if (kek == null) {
            if (requiresProtection) {
                throw new IllegalStateException(
                        "Refusing to store marketplace credentials unencrypted. Set "
                        + "web-experience.marketplace.credential-key before connecting a real store.");
            }
            // Mock credentials with no key configured: nothing secret to protect.
            return plaintext;
        }
        return CredentialCipher.encrypt(plaintext, kek, keyId);
    }

    /**
     * Decrypts a stored value, transparently passing through legacy plaintext.
     *
     * @param label identifies the row in the warning logged for a legacy value; must not itself be
     *              or contain a credential
     */
    public String reveal(String stored, String label) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!CredentialCipher.isEnvelope(stored)) {
            log.warn("Marketplace connection {} holds credentials written before envelope "
                    + "encryption. They will be re-encrypted next time the connection is saved.",
                    label);
            return stored;
        }
        if (kek == null) {
            throw new IllegalStateException(
                    "Stored credentials are encrypted but no marketplace credential key is "
                    + "configured. Set web-experience.marketplace.credential-key to the key they "
                    + "were written with.");
        }

        String storedKeyId = CredentialCipher.keyIdOf(stored);
        if (storedKeyId != null && !storedKeyId.equals(keyId)) {
            // Not fatal here — decryption below will fail loudly if the key genuinely differs —
            // but during a rotation this is the signal that a row has not been rewritten yet.
            log.warn("Marketplace connection {} was encrypted under key id '{}' but '{}' is "
                    + "configured.", label, storedKeyId, keyId);
        }
        return CredentialCipher.decrypt(stored, kek);
    }

    /**
     * Reads the configured key.
     *
     * <p>Accepts base64 or raw text and requires 32 bytes either way. A short key is rejected
     * rather than padded or hashed up to length: silently stretching a weak key produces something
     * that encrypts successfully while carrying far less entropy than AES-256 implies, and the
     * operator is never told.
     *
     * <p><b>Base64 is tried first but only accepted at the right length.</b> The two formats
     * overlap: a 32-character key drawn from hex or alphanumerics — which is exactly what someone
     * types when told "a 32-character key" — is also syntactically valid base64, and decodes to 24
     * bytes. Committing to the decode on syntax alone would reject that key as "24 bytes" while the
     * operator counts 32 characters and concludes the check is broken. Length decides instead of
     * syntax, so each interpretation is used only when it actually produces a usable key.
     */
    private SecretKey parseKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            return null;
        }
        String trimmed = encodedKey.trim();

        byte[] material = null;
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == KEY_BYTES) {
                material = decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // Falls through to the raw reading below.
        }

        if (material == null) {
            material = trimmed.getBytes(StandardCharsets.UTF_8);
        }

        if (material.length != KEY_BYTES) {
            // The key is present but unusable. Treating it as absent (rather than throwing) keeps
            // the failure at connect time, where the message is actionable, instead of preventing
            // the whole application from starting.
            log.error("Marketplace credential key must be {} bytes: got {} raw characters, which "
                    + "is {} bytes read either way. Generate one with 'openssl rand -base64 32'. "
                    + "Real-credential connections are refused until this is fixed.",
                    KEY_BYTES, trimmed.length(), material.length);
            return null;
        }
        return new SecretKeySpec(material, "AES");
    }

    /** Exposed for tests: whether two protectors hold the same key. */
    boolean hasSameKeyAs(CredentialProtector other) {
        if (kek == null || other == null || other.kek == null) {
            return false;
        }
        return MessageDigest.isEqual(kek.getEncoded(), other.kek.getEncoded());
    }
}
