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
    /**
     * One key signs; several may verify. That split is what lets a key be rotated without logging
     * everyone out — see {@link SigningKeySet}.
     */
    private final SigningKeySet keySet;
    private final RSAKey signingKey;
    private final RSASSASigner signer;

    public JwtService(WebExperienceProperties properties) {
        this.accessTokenTtl = Duration.ofMinutes(properties.getAccessTokenTtlMinutes());
        this.keySet = SigningKeySet.from(
                properties.getJwtSigningKey(),
                properties.getJwtPreviousKeys(),
                properties.isAllowEphemeralJwtKey());
        this.signingKey = keySet.activeKey();
        try {
            this.signer = new RSASSASigner(signingKey.toRSAPrivateKey());
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to initialise JWT signer", exception);
        }
    }

    /** Public keys other services verify against. Exposed at /.well-known/jwks.json. */
    public com.nimbusds.jose.jwk.JWKSet publicJwkSet() {
        return keySet.publicJwkSet();
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

            // Pick the key the token names rather than trying each in turn. An unknown kid means
            // the token was signed by something this service does not trust, and guessing would
            // only slow the rejection down.
            Optional<RSAKey> key = keySet.verificationKey(jwt.getHeader().getKeyID());
            if (key.isEmpty()) {
                return Optional.empty();
            }
            if (!jwt.verify(new RSASSAVerifier(key.get().toRSAPublicKey()))) {
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

}
