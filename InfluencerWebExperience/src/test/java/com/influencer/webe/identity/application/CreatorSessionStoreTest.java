package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.identity.infrastructure.DaoCreatorIdentityClient;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creator sessions survive a deploy, and the raw token never leaves the process (PR-40).
 *
 * <p>These used to live in a {@code ConcurrentHashMap}. The reason that had to change is not
 * multi-instance: an ASG instance refresh is the live step of every deploy here, so an in-memory
 * store signed out every creator on every release — including one halfway through editing a page.
 *
 * <p>The first test is the security property and the one worth guarding hardest: what reaches the
 * database must not be usable as a credential. A future "simplification" that stored the token
 * itself would pass every functional test in this file.
 */
class CreatorSessionStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CREATOR_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

    /** Records every call so a test can assert what was sent, and serves a fixed session back. */
    private static class RecordingDao extends DaoGatewayClient {

        final List<String> gets = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        final List<ObjectNode> posts = new ArrayList<>();
        JsonNode storedSession;

        RecordingDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            gets.add(path);
            if (path.startsWith("/creator-portal-sessions/")) {
                if (storedSession == null) {
                    // The real DAO throws on 404, and resolve() must treat that as "no session"
                    // rather than as a fault. Modelled here so the test exercises that path.
                    throw new IllegalStateException("404 from DAO");
                }
                return storedSession;
            }
            if (path.startsWith("/creator-identities/")) {
                ObjectNode identity = MAPPER.createObjectNode();
                identity.put("id", CREATOR_ID.toString());
                identity.put("email", "creator@example.com");
                identity.put("displayName", "A Creator");
                // The real endpoint also returns passwordHash. Included deliberately so the
                // projection test below is meaningful rather than vacuous.
                identity.put("passwordHash", "$2a$10$notarealhash");
                return identity;
            }
            return null;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            posts.add((ObjectNode) body);
            return body;
        }

        @Override
        public void delete(String path) {
            deletes.add(path);
        }
    }

    @Test
    @DisplayName("the raw token is never sent to the database")
    void tokenIsHashedBeforeStorage() {
        // The security property. A dump of creator_portal_sessions, a backup, or an errant query
        // must not yield a working credential -- so what is stored has to be a hash the token
        // cannot be recovered from. A change that stored the token itself would still pass every
        // other test here, which is why this one asserts the absence explicitly.
        RecordingDao dao = new RecordingDao();
        CreatorPortalService service = service(dao);

        CreatorPortalService.CreatorSession session =
                service.login("creator@example.com", "correct-horse");

        assertEquals(1, dao.posts.size(), "a session must be persisted");
        String storedHash = dao.posts.get(0).get("tokenHash").asText();

        assertNotEquals(session.token(), storedHash, "the stored value must not be the token");
        assertFalse(storedHash.contains(session.token()), "the token must not be embedded either");
        // SHA-256 hex: 64 characters, and fixed-length regardless of input.
        assertEquals(64, storedHash.length());
        assertTrue(storedHash.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("a session opened before a restart still resolves after it")
    void sessionSurvivesARestart() {
        // The whole point of the move. `service` and `restarted` are separate instances holding no
        // shared state, standing in for the process either side of a deploy.
        RecordingDao dao = new RecordingDao();
        CreatorPortalService service = service(dao);

        CreatorPortalService.CreatorSession opened =
                service.login("creator@example.com", "correct-horse");
        dao.storedSession = storedSessionFor(dao.posts.get(0).get("tokenHash").asText());

        CreatorPortalService restarted = service(dao);
        Optional<CreatorPortalService.CreatorSession> resolved = restarted.resolve(opened.token());

        assertTrue(resolved.isPresent(), "a deploy must not sign creators out");
        assertEquals(CREATOR_ID, resolved.get().creatorIdentityId());
    }

    @Test
    @DisplayName("an unknown token resolves to empty rather than throwing")
    void unknownTokenIsNotAnError() {
        // The DAO throws on 404. An unauthenticated request is an ordinary event, not a fault, so
        // that must not propagate -- if it did, every expired session would surface as a 502.
        RecordingDao dao = new RecordingDao();

        assertTrue(service(dao).resolve("never-issued").isEmpty());
    }

    @Test
    @DisplayName("resolving re-reads the store, so revocation is immediate")
    void resolveDoesNotCache() {
        // Caching here would reintroduce exactly the window the opaque-token design exists to
        // avoid: a brand revokes a creator's access and the creator keeps working until expiry.
        RecordingDao dao = new RecordingDao();
        CreatorPortalService service = service(dao);
        CreatorPortalService.CreatorSession opened =
                service.login("creator@example.com", "correct-horse");
        dao.storedSession = storedSessionFor(dao.posts.get(0).get("tokenHash").asText());

        service.resolve(opened.token());
        service.resolve(opened.token());

        long sessionReads = dao.gets.stream()
                .filter(path -> path.startsWith("/creator-portal-sessions/"))
                .count();
        assertEquals(2, sessionReads, "each resolve must re-read rather than trust a cache");
    }

    @Test
    @DisplayName("signing out revokes by hash, not by token")
    void logoutSendsTheHash() {
        RecordingDao dao = new RecordingDao();
        CreatorPortalService service = service(dao);
        CreatorPortalService.CreatorSession opened =
                service.login("creator@example.com", "correct-horse");

        service.logout(opened.token());

        assertEquals(1, dao.deletes.size());
        assertFalse(dao.deletes.get(0).contains(opened.token()),
                "the raw token must not appear in a URL, where it would reach access logs");
    }

    @Test
    @DisplayName("the resolved session never carries the identity's password hash")
    void resolvedSessionDoesNotCarryPasswordHash() {
        // GET /creator-identities/{id} returns the whole entity, passwordHash included. The client
        // projects it away; this pins that, because the projection is invisible at the call site
        // and would be easy to "simplify" into returning the DAO's answer unchanged.
        RecordingDao dao = new RecordingDao();
        CreatorPortalService service = service(dao);
        CreatorPortalService.CreatorSession opened =
                service.login("creator@example.com", "correct-horse");
        dao.storedSession = storedSessionFor(dao.posts.get(0).get("tokenHash").asText());

        CreatorPortalService.CreatorSession resolved = service.resolve(opened.token()).orElseThrow();

        assertEquals("creator@example.com", resolved.email());
        assertEquals("A Creator", resolved.displayName());
        assertFalse(resolved.toString().contains("notarealhash"),
                "a credential hash must not ride along in the session");
    }

    // ---- helpers -------------------------------------------------------

    private CreatorPortalService service(RecordingDao dao) {
        return new CreatorPortalService(new DaoCreatorIdentityClient(dao) {
            @Override
            public Optional<JsonNode> findByEmail(String email) {
                ObjectNode identity = MAPPER.createObjectNode();
                identity.put("id", CREATOR_ID.toString());
                identity.put("email", "creator@example.com");
                identity.put("displayName", "A Creator");
                // BCrypt of "correct-horse", so login() exercises the real password check.
                identity.put("passwordHash",
                        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                                .encode("correct-horse"));
                return Optional.of(identity);
            }
        }, dao, new LoginAttemptLimiter());
    }

    private ObjectNode storedSessionFor(String tokenHash) {
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("tokenHash", tokenHash);
        stored.put("creatorIdentityId", CREATOR_ID.toString());
        stored.put("expiresAt", Instant.now().plus(12, ChronoUnit.HOURS).toString());
        return stored;
    }
}
