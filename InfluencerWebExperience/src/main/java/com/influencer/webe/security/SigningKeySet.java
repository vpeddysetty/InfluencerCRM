package com.influencer.webe.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The signing key plus any predecessors still trusted for verification.
 *
 * <h3>Why rotation needs more than one key</h3>
 * With a single key, replacing it invalidates every token signed by the old one — so every user is
 * logged out the moment a rotation happens. That makes rotation something operators avoid, which is
 * precisely the wrong incentive for a credential that should be rotated regularly.
 *
 * <p>Splitting the roles fixes it: <strong>one key signs, several verify</strong>. A new key becomes
 * the signer immediately; the previous one stays trusted until the last token it signed has expired
 * — 30 minutes at the current access-token TTL. Nobody is logged out, and the old key can then be
 * dropped from configuration entirely.
 *
 * <h3>Configuration</h3>
 * <pre>
 *   web-experience.jwt-signing-key      the active key: signs new tokens, also verifies
 *   web-experience.jwt-previous-keys    comma-separated public JWKs, verification only
 * </pre>
 * Predecessors need only the public half. Retaining the private key of a rotated-out key would keep
 * a credential alive that no longer needs to exist — the point of rotating is to stop being able to
 * sign with it.
 *
 * <p>Verification is by {@code kid}: the token's header names the key, so the correct one is chosen
 * directly rather than by trying each in turn.
 */
public class SigningKeySet {

    private static final Logger log = LoggerFactory.getLogger(SigningKeySet.class);

    private final RSAKey activeKey;
    /** Every key trusted for verification — the active one plus retired predecessors, by {@code kid}. */
    private final Map<String, RSAKey> verificationKeys;

    private SigningKeySet(RSAKey activeKey, Map<String, RSAKey> verificationKeys) {
        this.activeKey = activeKey;
        this.verificationKeys = Map.copyOf(verificationKeys);
    }

    /**
     * Builds the keyset from configuration.
     *
     * @param activeJwk    the signing key, private half required
     * @param previousJwks comma-separated public JWKs still trusted for verification
     * @param allowEphemeral whether a missing active key may be generated. Single-process local
     *                       runs only: an ephemeral key cannot be verified by another instance.
     */
    public static SigningKeySet from(String activeJwk, String previousJwks, boolean allowEphemeral) {
        RSAKey active = resolveActiveKey(activeJwk, allowEphemeral);

        Map<String, RSAKey> keys = new LinkedHashMap<>();
        keys.put(active.getKeyID(), active);

        for (RSAKey previous : parsePreviousKeys(previousJwks)) {
            if (keys.containsKey(previous.getKeyID())) {
                // Listing the active key as a predecessor is harmless but signals a config mistake
                // — most likely a rotation that was never completed.
                log.warn("Key {} appears as both the active key and a predecessor; ignoring the "
                        + "duplicate", previous.getKeyID());
                continue;
            }
            keys.put(previous.getKeyID(), previous);
        }

        if (keys.size() > 1) {
            log.info("Signing with key {}. Still verifying {} retired key(s): {}. Remove them once "
                            + "the longest access-token lifetime has elapsed since rotation.",
                    active.getKeyID(), keys.size() - 1,
                    keys.keySet().stream().filter(id -> !id.equals(active.getKeyID())).toList());
        }

        return new SigningKeySet(active, keys);
    }

    public RSAKey activeKey() {
        return activeKey;
    }

    /**
     * The key a token was signed with, chosen by its {@code kid} header.
     *
     * <p>A token whose {@code kid} is unknown is rejected rather than tried against every key: an
     * unrecognised key id means the token was signed by something this service does not trust, and
     * guessing would only slow the rejection down.
     */
    public Optional<RSAKey> verificationKey(String keyId) {
        if (keyId == null || keyId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(verificationKeys.get(keyId));
    }

    /** Public halves only, for the JWKS endpoint other services verify against. */
    public JWKSet publicJwkSet() {
        List<com.nimbusds.jose.jwk.JWK> publicKeys = new ArrayList<>();
        verificationKeys.values().forEach(key -> publicKeys.add(key.toPublicJWK()));
        return new JWKSet(publicKeys);
    }

    public int size() {
        return verificationKeys.size();
    }

    // ------------------------------------------------------------------ parsing

    private static RSAKey resolveActiveKey(String activeJwk, boolean allowEphemeral) {
        if (activeJwk != null && !activeJwk.isBlank()) {
            try {
                RSAKey parsed = RSAKey.parse(activeJwk);
                if (parsed.toRSAPrivateKey() == null) {
                    throw new IllegalStateException(
                            "web-experience.jwt-signing-key must include the private key — it signs tokens");
                }
                return parsed;
            } catch (ParseException | com.nimbusds.jose.JOSEException exception) {
                throw new IllegalStateException("web-experience.jwt-signing-key is not a valid RSA JWK", exception);
            }
        }

        if (!allowEphemeral) {
            throw new IllegalStateException(
                    "web-experience.jwt-signing-key is not configured. An ephemeral key cannot be "
                            + "verified by another instance or service, so tokens would fail "
                            + "intermittently. Set a persistent RSA JWK, or set "
                            + "web-experience.allow-ephemeral-jwt-key=true for a single-process local run.");
        }

        log.warn("No signing key configured; generating an ephemeral RSA key. Tokens will not "
                + "survive restart and cannot be verified by other instances.");
        return generate();
    }

    private static List<RSAKey> parsePreviousKeys(String previousJwks) {
        List<RSAKey> keys = new ArrayList<>();
        if (previousJwks == null || previousJwks.isBlank()) {
            return keys;
        }

        // JWKs contain commas, so a plain split would shred them. Splitting on the boundary between
        // one JSON object and the next keeps each intact.
        for (String candidate : previousJwks.split("(?<=\\})\\s*,\\s*(?=\\{)")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                keys.add(RSAKey.parse(trimmed));
            } catch (ParseException exception) {
                // One malformed predecessor must not prevent startup: the active key still works,
                // and refusing to boot would turn a stale config entry into an outage.
                log.error("Ignoring an unparseable entry in web-experience.jwt-previous-keys. "
                        + "Tokens signed by that key will be rejected.", exception);
            }
        }
        return keys;
    }

    private static RSAKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate a development signing key", exception);
        }
    }
}
