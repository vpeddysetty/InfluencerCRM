package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoCreatorIdentityClient;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.ConcurrentHashMap;

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
public class CreatorPortalService {

    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private final DaoCreatorIdentityClient creatorIdentityClient;
    private final DaoGatewayClient daoGatewayClient;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    /**
     * In-memory session store.
     *
     * <p>Deliberately not Redis-backed like the operator session: the creator portal is new, has no
     * multi-instance deployment yet, and adding a shared store before there is a second instance
     * would be infrastructure ahead of need. It does mean a restart signs creators out — acceptable
     * for a portal, and the reason this is called out rather than left to be discovered.
     */
    private final Map<String, CreatorSession> sessions = new ConcurrentHashMap<>();

    public CreatorPortalService(DaoCreatorIdentityClient creatorIdentityClient,
                                DaoGatewayClient daoGatewayClient) {
        this.creatorIdentityClient = creatorIdentityClient;
        this.daoGatewayClient = daoGatewayClient;
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
        JsonNode identity = creatorIdentityClient.findByEmail(normalize(email))
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));
        String hash = identity.hasNonNull("passwordHash") ? identity.get("passwordHash").asText() : null;
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            // Same message as an unknown email: distinguishing them tells an attacker which
            // addresses are registered.
            throw new IllegalArgumentException("Invalid credentials");
        }
        return openSession(identity);
    }

    public Optional<CreatorSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        CreatorSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
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
        CreatorSession session = new CreatorSession(
                token,
                UUID.fromString(identity.get("id").asText()),
                identity.get("email").asText(),
                identity.hasNonNull("displayName") ? identity.get("displayName").asText() : null,
                Instant.now().plus(SESSION_TTL));
        sessions.put(token, session);
        return session;
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
