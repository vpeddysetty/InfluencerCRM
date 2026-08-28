package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.infrastructure.DaoCreatorIdentityClient;
import com.influencer.webe.shared.application.CreatorSessionVerifier;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-in and data access for creators (roadmap Stage 4).
 *
 * <p>A creator is not a tenant, and this service exists so that stays true. {@code SessionService}
 * mints a token around a {@code TenantContext} — an account, an active brand, a set of
 * {@code account_role} permissions. A creator has none of those: they are a person several
 * unrelated brands each hold a row about. Forcing them through that path would mean inventing an
 * account for them, and an account is exactly what must not exist, or brand-scoped permission
 * checks would start returning true for a creator.
 *
 * <p>Sessions here are opaque random tokens held server-side rather than JWTs. A creator session
 * carries no claims worth signing — the only question is "which creator rows may this person
 * see", and that is re-read from the database on every call so a brand revoking a link takes
 * effect immediately rather than at token expiry.
 */
@Service
public class CreatorPortalService implements CreatorSessionVerifier {

    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final DaoCreatorIdentityClient creatorIdentityClient;
    private final DaoGatewayClient daoGatewayClient;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    /*
     * Sessions live in identity.creator_portal_sessions, not in this process (PR-40).
     *
     * They used to be a ConcurrentHashMap held here, called out at the time as
     * infrastructure-ahead-of-need while the portal had no real users. What stopped that being
     * acceptable is not multi-instance: an ASG instance refresh is the LIVE STEP of every deploy
     * in this project, so an in-memory store signed out every creator on every release — a
     * creator halfway through editing a page would lose their session to a deploy they cannot see
     * coming.
     *
     * Only the SHA-256 hash of the token is sent to the DAO. The raw value is returned to the
     * caller once and never persisted or logged, so a dump of that table cannot be replayed.
     */

    public CreatorPortalService(DaoCreatorIdentityClient creatorIdentityClient,
                                DaoGatewayClient daoGatewayClient,
                                LoginAttemptLimiter loginAttemptLimiter) {
        this.creatorIdentityClient = creatorIdentityClient;
        this.daoGatewayClient = daoGatewayClient;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    public CreatorSession signup(String email, String password, String displayName) {
        String normalized = normalize(email);
        if (creatorIdentityClient.findByEmail(normalized).isPresent()) {
            throw new IllegalArgumentException("An account with this email already exists");
        }
        JsonNode identity = creatorIdentityClient.create(
                normalized, passwordEncoder.encode(password), displayName);
        return openSession(identity);
    }

    public CreatorSession login(String email, String password) {
        String normalized = normalize(email);

        // BEFORE the lookup and before BCrypt, which is the entire point. BCrypt costs ~100ms by
        // design, so an unthrottled login endpoint is a denial-of-service amplifier: a few hundred
        // concurrent guesses saturate the request threads with work the server chose to make
        // expensive. Refusing here costs a map lookup instead.
        if (!loginAttemptLimiter.allow(normalized)) {
            throw new IllegalArgumentException(
                    "Too many sign-in attempts. Please wait a few minutes and try again.");
        }

        JsonNode identity = creatorIdentityClient.findByEmail(normalized).orElse(null);
        String hash = identity != null && identity.hasNonNull("passwordHash")
                ? identity.get("passwordHash").asText() : null;
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            loginAttemptLimiter.recordFailure(normalized);
            // Same message as an unknown email: distinguishing them tells an attacker which
            // addresses are registered. An unknown address is also counted, so enumerating them
            // is throttled at the same rate as guessing a password.
            throw new IllegalArgumentException("Invalid credentials");
        }
        loginAttemptLimiter.recordSuccess(normalized);
        return openSession(identity);
    }

    /**
     * Resolve a token to its session, re-reading the store every time.
     *
     * <p>The re-read is the point, not an inefficiency: it is what makes revoking a creator's
     * access take effect immediately rather than at token expiry. Caching here would reintroduce
     * exactly the window the opaque-token design exists to avoid.
     *
     * <p>The DAO answers 404 for unknown, expired and revoked alike, so this method does not
     * distinguish them either — the caller needs one answer, "not usable".
     */
    public Optional<CreatorSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        JsonNode stored;
        try {
            stored = daoGatewayClient.get("/creator-portal-sessions/" + hash(token), null);
        } catch (RuntimeException e) {
            // A miss surfaces as a gateway exception because the DAO throws on 404. That is the
            // same outcome as "no session" for the caller, and must not be an error: an
            // unauthenticated request is an ordinary event, not a fault.
            return Optional.empty();
        }
        if (stored == null || !stored.hasNonNull("creatorIdentityId")) {
            return Optional.empty();
        }

        // The identity is re-read too, so a display name or email changed since sign-in is
        // current, and so a deleted identity cannot keep resolving.
        UUID creatorIdentityId = UUID.fromString(stored.get("creatorIdentityId").asText());
        Optional<JsonNode> identity = creatorIdentityClient.findById(creatorIdentityId);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        JsonNode found = identity.get();
        return Optional.of(new CreatorSession(
                token,
                creatorIdentityId,
                found.path("email").asText(""),
                found.hasNonNull("displayName") ? found.get("displayName").asText() : null,
                Instant.parse(stored.get("expiresAt").asText())));
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            daoGatewayClient.delete("/creator-portal-sessions/" + hash(token));
        } catch (RuntimeException e) {
            // Signing out must not fail loudly. The session either did not exist or is already
            // revoked; either way the caller's intent — "end this session" — holds.
        }
    }

    /**
     * SHA-256 of the token, hex encoded.
     *
     * <p>Not BCrypt, and the difference matters here: the token is 256 bits of {@code
     * SecureRandom}, so there is no low-entropy secret to brute-force and nothing a slow hash
     * would protect. BCrypt would cost ~100ms on EVERY authenticated request for no security
     * gain. The reason to hash at all is that a database read must not yield a working credential.
     */
    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; this cannot happen on a conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link #resolve}, publishing only the creator identity. The filter in
     * {@code security} needs to know who is calling and nothing else — keeping the token and the
     * rest of the session inside this context is the point of the narrower port.
     */
    @Override
    public Optional<UUID> verifyCreatorToken(String token) {
        return resolve(token).map(CreatorSession::creatorIdentityId);
    }

    /**
     * A creator claims one of a brand's creator records as themselves.
     *
     * <p>Created as {@code claimed}, never {@code confirmed}. Most creator rows carry no email and
     * handles repeat across brands, so a claim is an assertion with nothing behind it until the
     * brand approves — confirming on the creator's say-so would hand them another brand's
     * negotiated rate.
     */
    public JsonNode claim(UUID identityId, UUID creatorId, UUID brandId) {
        return creatorIdentityClient.link(identityId, creatorId, brandId, "claimed", null);
    }

    /**
     * Every brand-side record this creator has been confirmed against.
     *
     * <p>The links are the tenancy rule, inverted: instead of "this brand owns these rows", it is
     * "these rows have been confirmed as me". Unconfirmed claims are excluded, so a claim grants
     * no visibility at all.
     */
    public List<Map<String, Object>> collaborations(UUID identityId) {
        JsonNode links = creatorIdentityClient.links(identityId, "confirmed");
        List<Map<String, Object>> out = new ArrayList<>();
        if (links == null || !links.isArray()) {
            return out;
        }
        for (JsonNode link : links) {
            UUID creatorId = UUID.fromString(link.get("creatorId").asText());
            UUID brandId = UUID.fromString(link.get("brandId").asText());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("creatorId", creatorId);
            row.put("brandId", brandId);
            row.put("brandName", brandName(brandId));
            row.put("assignments", assignmentsFor(creatorId));
            out.add(row);
        }
        return out;
    }

    private String brandName(UUID brandId) {
        try {
            JsonNode brand = daoGatewayClient.get("/tenancy/brands/" + brandId, null);
            return brand != null && brand.hasNonNull("name") ? brand.get("name").asText() : "Brand";
        } catch (Exception exception) {
            // A brand that cannot be read must not blank the whole portal.
            return "Brand";
        }
    }

    /**
     * The creator's campaign assignments for one brand-side record.
     *
     * <p>Queried by {@code creatorId}, which is per-brand, so this cannot return another brand's
     * assignments even if the query were wrong about the brand.
     */
    private JsonNode assignmentsFor(UUID creatorId) {
        try {
            return daoGatewayClient.get("/campaign-creators", Map.of("creatorId", creatorId.toString()));
        } catch (Exception exception) {
            return null;
        }
    }

    private CreatorSession openSession(JsonNode identity) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID creatorIdentityId = UUID.fromString(identity.get("id").asText());
        Instant expiresAt = Instant.now().plus(SESSION_TTL);

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("tokenHash", hash(token));
        body.put("creatorIdentityId", creatorIdentityId.toString());
        body.put("expiresAt", expiresAt.toString());
        daoGatewayClient.post("/creator-portal-sessions", body);

        return new CreatorSession(
                token,
                creatorIdentityId,
                identity.get("email").asText(),
                identity.hasNonNull("displayName") ? identity.get("displayName").asText() : null,
                expiresAt);
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    public record CreatorSession(String token,
                                 UUID creatorIdentityId,
                                 String email,
                                 String displayName,
                                 Instant expiresAt) {
    }
}
