package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invitation that breaks the bootstrap circularity (roadmap PR-41).
 *
 * <p>Before this, {@code PageCollaborationService.invite} refused unless the creator already held
 * a confirmed link, and the only route to one was an out-of-band UUID exchange — so the whole
 * collaboration backend was complete and unreachable. These tests cover the lifecycle, but the two
 * that matter most are the security ones: the token must not reach the database, and the preview
 * must not leak the page.
 */
class CreatorInvitationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID INVITER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /** Records what was sent, and serves a configurable invitation back. */
    private static class RecordingDao extends DaoGatewayClient {

        final List<ObjectNode> posts = new ArrayList<>();
        final List<String> postPaths = new ArrayList<>();
        JsonNode storedInvite;
        boolean failNextPost;

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
            if (path.startsWith("/creator-invites/by-token/")) {
                if (storedInvite == null) {
                    throw new IllegalStateException("404 from DAO");
                }
                return storedInvite;
            }
            if (path.startsWith("/creator-invites")) {
                ArrayNode rows = MAPPER.createArrayNode();
                if (storedInvite != null) {
                    rows.add(storedInvite);
                }
                return rows;
            }
            return null;
        }

        @Override
        public JsonNode post(String path, JsonNode body) {
            if (failNextPost) {
                failNextPost = false;
                throw new IllegalStateException("409 from DAO");
            }
            postPaths.add(path);
            posts.add((ObjectNode) body);
            return body;
        }
    }

    /** Captures messages instead of sending them, and can be told to fail. */
    private static class CapturingEmail implements EmailPort {

        final List<Message> sent = new ArrayList<>();
        boolean fail;

        @Override
        public Result send(Message message) {
            if (fail) {
                // The port REPORTS failure rather than throwing it, and the `log` provider --
                // today's configured default -- returns sent=false having written a line. A
                // service that only caught exceptions would call that delivered.
                return Result.failed("test", "SES unavailable");
            }
            sent.add(message);
            return Result.sent("test", "message-id");
        }

        @Override
        public String provider() {
            return "test";
        }
    }

    @Test
    @DisplayName("the invitation token never reaches the database")
    void tokenIsHashedBeforeStorage() {
        // The security property. Whoever holds this token can become a confirmed collaborator on
        // the brand's unpublished pages, so a database dump must not contain working invitations.
        // Asserted as an absence, because a change that stored the token would pass every
        // functional test in this file.
        RecordingDao dao = new RecordingDao();
        CapturingEmail email = new CapturingEmail();

        CreatorInvitationService.InvitationCreated created =
                service(dao, email).invite(BRAND, "creator@example.com", null, null, INVITER, "Acme");

        ObjectNode stored = dao.posts.get(0);
        assertNotEquals(created.token(), stored.get("tokenHash").asText());
        assertFalse(stored.toString().contains(created.token()),
                "the token must not appear anywhere in what is stored");
    }

    @Test
    @DisplayName("the token is emailed, and the email is the only place it appears")
    void tokenTravelsOnlyInTheEmail() {
        RecordingDao dao = new RecordingDao();
        CapturingEmail email = new CapturingEmail();

        CreatorInvitationService.InvitationCreated created =
                service(dao, email).invite(BRAND, "creator@example.com", null, null, INVITER, "Acme");

        assertEquals(1, email.sent.size());
        assertTrue(email.sent.get(0).textBody().contains(created.token()),
                "the recipient needs the token to accept");
        assertTrue(created.delivered());
    }

    @Test
    @DisplayName("a failed send does not throw the invitation away")
    void deliveryFailureKeepsTheInvitation() {
        // SES is in the sandbox as this ships, so delivery failure is the EXPECTED path for now,
        // not an edge case. Discarding a valid invitation because the mail server was briefly
        // unreachable would turn a retryable problem into a lost one -- and the brand can pass the
        // link on themselves, which is what makes the feature usable at all today.
        RecordingDao dao = new RecordingDao();
        CapturingEmail email = new CapturingEmail();
        email.fail = true;

        CreatorInvitationService.InvitationCreated created =
                service(dao, email).invite(BRAND, "creator@example.com", null, null, INVITER, "Acme");

        assertEquals(1, dao.posts.size(), "the invitation is still stored");
        assertFalse(created.delivered(), "and the caller is told delivery failed");
        assertFalse(created.token().isBlank(), "so the link can be passed on by hand");
    }

    @Test
    @DisplayName("a second pending invitation is refused, not silently duplicated")
    void duplicatePendingInvitationIsAConflict() {
        // The partial unique index enforces this. Two live tokens for one brand+email would mean
        // revoking the one the UI shows still lets the other in -- a revocation that looks like it
        // worked and did not.
        RecordingDao dao = new RecordingDao();
        dao.failNextPost = true;

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service(dao, new CapturingEmail())
                        .invite(BRAND, "creator@example.com", null, null, INVITER, "Acme"));

        assertTrue(error.getMessage().contains("409"), "the UI needs a conflict it can act on");
    }

    @Test
    @DisplayName("the preview shows the brand but never the page")
    void previewDoesNotLeakThePage() {
        // A GET that rendered stored unpublished content would be fetched automatically by email
        // scanners, Slack and WhatsApp unfurlers, and link prewarmers -- so one forwarded
        // invitation would leak an unreleased campaign into a channel nobody meant to share it in.
        RecordingDao dao = new RecordingDao();
        ObjectNode invite = MAPPER.createObjectNode();
        invite.put("status", "pending");
        invite.put("email", "creator@example.com");
        invite.put("brandId", BRAND.toString());
        invite.put("expiresAt", Instant.now().plus(7, ChronoUnit.DAYS).toString());
        invite.put("landingTemplateId", UUID.randomUUID().toString());
        // Fields a careless projection might pass straight through.
        invite.put("pageName", "Unreleased winter launch");
        invite.put("publicSlug", "winter-secret");
        dao.storedInvite = invite;

        JsonNode preview = service(dao, new CapturingEmail()).preview("some-token");

        assertTrue(preview.path("hasPage").asBoolean(), "the creator should know there is a page");
        assertFalse(preview.toString().contains("Unreleased winter launch"),
                "the page's name must not be in a pre-acceptance response");
        assertFalse(preview.toString().contains("winter-secret"),
                "nor its slug, which would be enough to find it");
    }

    @Test
    @DisplayName("an unknown token is not found rather than erroring")
    void unknownTokenIsNotFound() {
        RecordingDao dao = new RecordingDao();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service(dao, new CapturingEmail()).preview("never-issued"));

        assertTrue(error.getMessage().contains("404"));
    }

    @Test
    @DisplayName("redeeming sends the hash, never the token")
    void redeemSendsTheHash() {
        RecordingDao dao = new RecordingDao();
        CreatorInvitationService service = service(dao, new CapturingEmail());

        service.redeem("a-token-value", "A Creator", null);

        assertEquals(1, dao.postPaths.size());
        assertFalse(dao.postPaths.get(0).contains("a-token-value"),
                "the raw token must not appear in a URL, where it would reach access logs");
        assertTrue(dao.postPaths.get(0).endsWith("/redeem"));
    }

    @Test
    @DisplayName("an invalid email is refused before anything is stored")
    void invalidEmailIsRefused() {
        RecordingDao dao = new RecordingDao();

        assertThrows(ResponseStatusException.class,
                () -> service(dao, new CapturingEmail())
                        .invite(BRAND, "not-an-address", null, null, INVITER, "Acme"));

        assertTrue(dao.posts.isEmpty(), "nothing may be written for a refused invitation");
    }

    // ---- helpers -------------------------------------------------------

    private CreatorInvitationService service(RecordingDao dao, EmailPort email) {
        // The comma-separated base URL is deliberate: ui-base-url may list several hostnames for
        // CORS, and MemberInvitationService learned live that using the whole string produces
        // "https://a.com,https://b.com/invite?token=..." -- not a link. Passed here so the
        // stripping is exercised rather than assumed.
        return new CreatorInvitationService(dao, email, "",
                "https://tejdux.com,https://www.tejdux.com/");
    }
}
