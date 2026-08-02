package com.influencer.webe.security;

import com.influencer.webe.config.WebExperienceProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues and verifies the RS256 access tokens that carry tenancy and authorization claims.
 *
 * <p>Replaces the previous opaque {@code UUID.randomUUID()} tokens held in an in-memory map, which
 * could not be validated by a second instance and carried no claims. Stateless verification is the
 * prerequisite for extracting services in Phase 5 (docs/ddd-roadmap.md).
 *
 * <p>Claims:
 * <pre>
 *   sub    user id
 *   acc    account id
 *   brand  the ACTIVE brand for this session (the tenancy key)
 *   email  user email
 *   role   effective role for the active brand
 *   perms  permissions derived from that role
 *   brands every brand this caller may switch to
 * </pre>
 *
 * <p>{@code role} and {@code perms} are scoped to {@code brand}, not to the user: the same person
 * can be MANAGER on one brand and ANALYST on another, so switching brands re-mints the token.
 */
@Service
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String CLAIM_ACCOUNT_ID = "acc";
    public static final String CLAIM_BRAND_ID = "brand";
    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_ACCESSIBLE_BRANDS = "brands";
    public static final String CLAIM_PROVIDER = "provider";

    private static final String ISSUER = "influencrm-web-experience";

    private final Duration accessTokenTtl;
    /** Whether a missing signing key may fall back to an ephemeral one. Local single-process only. */
    private final boolean allowEphemeralKey;
    private final RSAKey signingKey;
    private final RSASSASigner signer;
    private final RSASSAVerifier verifier;

    public JwtService(WebExperienceProperties properties) {
        this.accessTokenTtl = Duration.ofMinutes(properties.getAccessTokenTtlMinutes());
        this.allowEphemeralKey = properties.isAllowEphemeralJwtKey();
        this.signingKey = resolveSigningKey(properties.getJwtSigningKey());
        try {
            this.signer = new RSASSASigner(signingKey.toRSAPrivateKey());
            this.verifier = new RSASSAVerifier(signingKey.toRSAPublicKey());
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to initialise JWT signer", exception);
        }
    }

    public String issueAccessToken(TenantContext context, String provider) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(context.userId().toString())
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTokenTtl)))
                .jwtID(UUID.randomUUID().toString())
                .claim(CLAIM_ACCOUNT_ID, context.accountId().toString())
                .claim(CLAIM_BRAND_ID, context.brandId().toString())
                .claim(CLAIM_EMAIL, context.email())
                .claim(CLAIM_ROLE, context.role() == null ? null : context.role().name())
                .claim(CLAIM_PERMISSIONS, List.copyOf(context.permissionKeys()))
                .claim(CLAIM_ACCESSIBLE_BRANDS, context.accessibleBrandIds().stream()
                        .map(UUID::toString)
                        .collect(Collectors.toList()))
                .claim(CLAIM_PROVIDER, provider)
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign access token", exception);
        }
        return jwt.serialize();
    }

    /**
     * Verifies signature, issuer and expiry, then rebuilds the caller's tenant context.
     * Returns empty for any token that fails verification — callers must treat that as unauthenticated.
     */
    public Optional<TenantContext> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(verifier)) {
                return Optional.empty();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) {
                return Optional.empty();
            }
            Date expiry = claims.getExpirationTime();
            if (expiry == null || expiry.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }

            return Optional.of(new TenantContext(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.getStringClaim(CLAIM_ACCOUNT_ID)),
                    UUID.fromString(claims.getStringClaim(CLAIM_BRAND_ID)),
                    claims.getStringClaim(CLAIM_EMAIL),
                    AccountRole.parse(claims.getStringClaim(CLAIM_ROLE)).orElse(null),
                    readPermissions(claims),
                    readUuidSet(claims, CLAIM_ACCESSIBLE_BRANDS)));
        } catch (ParseException | JOSEException | RuntimeException exception) {
            // Malformed, tampered, or foreign token — indistinguishable from absent for our purposes.
            // RuntimeException is caught deliberately: the JOSE parser throws unchecked exceptions
            // (NullPointerException among them) on some malformed input, and an unauthenticated
            // caller must never be able to turn a bad token into a 500.
            return Optional.empty();
        }
    }

    /**
     * The signing key as a JWK string.
     *
     * <p>Exists so a test can prove that two instances sharing one configured key verify each
     * other's tokens — the property that makes multi-instance and multi-service deployment work.
     * Not called by production code.
     */
    String exportSigningKeyForTesting() {
        return signingKey.toJSONString();
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    /**
     * Reads the permission claim, discarding any key this build does not recognise.
     *
     * <p>Unknown keys are dropped rather than retained: a token minted by a newer build must never
     * grant a capability this one cannot reason about.
     */
    private Set<Permission> readPermissions(JWTClaimsSet claims) throws ParseException {
        List<String> values = claims.getStringListClaim(CLAIM_PERMISSIONS);
        if (values == null) {
            return Set.of();
        }
        Set<Permission> permissions = new LinkedHashSet<>();
        for (String value : values) {
            Permission.fromKey(value).ifPresent(permissions::add);
        }
        return permissions;
    }

    private Set<UUID> readUuidSet(JWTClaimsSet claims, String claimName) throws ParseException {
        List<String> values = claims.getStringListClaim(claimName);
        if (values == null) {
            return Set.of();
        }
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String value : values) {
            parsed.add(UUID.fromString(value));
        }
        return parsed;
    }

    /**
     * Loads the configured RSA key, or generates an ephemeral one for local development.
     *
     * <p>An ephemeral key means every restart invalidates outstanding tokens and a second instance
     * cannot verify the first's tokens — acceptable for a single local process, never for a
     * deployed environment. Configure {@code web-experience.jwt-signing-key} there.
     */
    private RSAKey resolveSigningKey(String configuredJwk) {
        if (configuredJwk != null && !configuredJwk.isBlank()) {
            try {
                RSAKey parsed = RSAKey.parse(configuredJwk);
                if (parsed.toRSAPrivateKey() == null) {
                    throw new IllegalStateException("web-experience.jwt-signing-key must include the private key");
                }
                return parsed;
            } catch (ParseException | JOSEException exception) {
                throw new IllegalStateException("web-experience.jwt-signing-key is not a valid RSA JWK", exception);
            }
        }

        // Refuse to start rather than silently issue tokens no other instance can verify.
        //
        // An ephemeral key is survivable in a single local process and catastrophic anywhere else:
        // a second instance rejects the first's tokens, which surfaces as intermittent 401s that
        // look like a session bug rather than a configuration one. Now that Workflow runs as a
        // separate service, "anywhere else" includes any deployment.
        if (!allowEphemeralKey) {
            throw new IllegalStateException(
                    "web-experience.jwt-signing-key is not configured. An ephemeral key cannot be "
                            + "verified by another instance or service, so tokens would fail "
                            + "intermittently. Set a persistent RSA JWK, or set "
                            + "web-experience.allow-ephemeral-jwt-key=true for a single-process "
                            + "local run.");
        }

        log.warn("No web-experience.jwt-signing-key configured; generating an ephemeral RSA key. "
                + "Tokens will not survive restart and cannot be verified by other instances. "
                + "This is permitted only because web-experience.allow-ephemeral-jwt-key=true.");
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
