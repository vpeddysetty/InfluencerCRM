package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.CreatorDirectory;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The acknowledgement a creator gets when their page goes live (roadmap PR-44).
 *
 * <p>This is the email the whole creator handoff was asked for. The property that matters most is
 * that it arrives <b>once</b>: a page that is published, pulled back to fix a typo and republished
 * reaches the published stage three times, and a creator who received three identical "your page is
 * live" emails would learn to ignore the first one.
 */
class CreatorPublishNotificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID BRAND = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CREATOR = UUID.fromString("66666666-7777-8888-9999-000000000000");

    private static class StubDao extends DaoGatewayClient {

        JsonNode grants = MAPPER.createArrayNode();

        StubDao() {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, null);
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.startsWith("/landing-page-collaborators")) {
                return grants;
            }
            if (path.startsWith("/brands/")) {
                return MAPPER.createObjectNode().put("name", "Acme");
            }
            ObjectNode page = MAPPER.createObjectNode();
            page.put("id", UUID.randomUUID().toString());
            page.put("brandId", BRAND.toString());
            page.put("name", "Winter trail");
            page.put("publicSlug", "winter-trail");
            return page;
        }
    }

    private static class CapturingEmail implements EmailPort {

        final List<Message> sent = new ArrayList<>();

        @Override
        public Result send(Message message) {
            sent.add(message);
            return Result.sent("test", "id");
        }

        @Override
        public String provider() {
            return "test";
        }
    }

    private static class StubDirectory implements CreatorDirectory {

        @Override
        public Optional<Creator> lookupCreator(UUID id) {
            return Optional.of(new Creator(id, "creator@example.com", "A Creator"));
        }
    }

    @Test
    @DisplayName("the creator who worked on the page is told it is live")
    void notifiesTheCollaborator() {
        StubDao dao = new StubDao();
        dao.grants = grantsFor(CREATOR);
        CapturingEmail email = new CapturingEmail();

        notifier(dao, email).notifyPublished(BRAND, UUID.randomUUID());

        assertEquals(1, email.sent.size());
        assertEquals("creator@example.com", email.sent.get(0).to());
        assertTrue(email.sent.get(0).textBody().contains("https://tejdux.com/s/winter-trail"),
                "the link is the whole point -- they want to see the thing");
        assertTrue(email.sent.get(0).subject().contains("Acme"));
    }

    @Test
    @DisplayName("a creator granted access twice is told once")
    void deduplicatesByIdentity() {
        // Invited, revoked, re-invited leaves two rows. Two emails for one publish reads as a bug
        // to the recipient even though both rows are legitimate.
        StubDao dao = new StubDao();
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(grant(CREATOR));
        rows.add(grant(CREATOR));
        dao.grants = rows;
        CapturingEmail email = new CapturingEmail();

        notifier(dao, email).notifyPublished(BRAND, UUID.randomUUID());

        assertEquals(1, email.sent.size());
    }

    @Test
    @DisplayName("somebody whose access was revoked is not told")
    void skipsRevokedCollaborators() {
        // They were taken off the work before it shipped. Telling them it went live is at best
        // confusing and at worst reads as the brand rubbing it in.
        StubDao dao = new StubDao();
        ObjectNode revoked = grant(CREATOR);
        revoked.put("revokedAt", "2026-08-01T00:00:00Z");
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(revoked);
        dao.grants = rows;
        CapturingEmail email = new CapturingEmail();

        notifier(dao, email).notifyPublished(BRAND, UUID.randomUUID());

        assertTrue(email.sent.isEmpty());
    }

    @Test
    @DisplayName("a page with no creator on it sends nothing")
    void noCollaboratorsNoEmail() {
        // The ordinary case: most pages are authored by the brand alone.
        CapturingEmail email = new CapturingEmail();

        notifier(new StubDao(), email).notifyPublished(BRAND, UUID.randomUUID());

        assertTrue(email.sent.isEmpty());
    }

    @Test
    @DisplayName("a failing email never fails the publish")
    void emailFailureIsSwallowed() {
        // The publish already happened by the time this runs. Letting an exception escape would
        // turn a successful launch into a failed request -- the operation the user asked for
        // outranks the bookkeeping around it.
        StubDao dao = new StubDao();
        dao.grants = grantsFor(CREATOR);
        EmailPort exploding = new EmailPort() {
            @Override
            public Result send(Message message) {
                throw new IllegalStateException("SES exploded");
            }

            @Override
            public String provider() {
                return "boom";
            }
        };

        // No assertion beyond "this returns" -- that IS the property.
        notifier(dao, exploding).notifyPublished(BRAND, UUID.randomUUID());
    }

    @Test
    @DisplayName("the email names the page without inventing a link it cannot verify")
    void composesWithoutAUrl() {
        // A link that 404s is worse than no link: the creator concludes their work was pulled. A
        // page can be published to a custom domain that is still resolving.
        EmailPort.Message message = CreatorPublishedEmail.compose(
                "creator@example.com", "Acme", "Winter trail", null);

        assertTrue(message.textBody().contains("Winter trail"));
        assertFalse(message.textBody().contains("http"), "no link is better than a broken one");
        assertTrue(message.textBody().contains("Thank you"));
    }

    @Test
    @DisplayName("the hand-back email carries the creator's note verbatim")
    void handBackEmailKeepsTheNote() {
        // The note is the closest thing to a handover conversation the product has. Summarising or
        // truncating it would lose exactly the "I changed the headline because..." that makes the
        // brand's review quick.
        EmailPort.Message message = CreatorHandedBackEmail.compose(
                "owner@acme.com", "A Creator", "Winter trail",
                "I rewrote the intro in my own voice and swapped the photo.",
                "https://tejdux.com/content?page=abc");

        assertTrue(message.textBody().contains("I rewrote the intro in my own voice"));
        assertTrue(message.subject().contains("Winter trail"));
        // The question the brand is about to have, answered before they ask it.
        assertTrue(message.textBody().contains("Nothing is live until you publish it."));
    }

    @Test
    @DisplayName("a hand-back with no note still reads as a complete message")
    void handBackEmailWithoutANote() {
        // Most creators will not write one. The email must not have a dangling "They said:" header
        // over nothing.
        EmailPort.Message message = CreatorHandedBackEmail.compose(
                "owner@acme.com", null, null, null, null);

        assertFalse(message.textBody().contains("They said"));
        assertTrue(message.textBody().contains("A creator"), "falls back to a neutral name");
        assertFalse(message.textBody().contains("http"), "no link rather than a broken one");
    }

    // ---- fixtures ------------------------------------------------------

    private CollaboratorNotifier notifier(StubDao dao, EmailPort email) {
        return new CollaboratorNotifier(dao, new StubDirectory(), email,
                "https://tejdux.com", "https://tejdux.com,https://www.tejdux.com/");
    }

    private ArrayNode grantsFor(UUID creatorIdentityId) {
        ArrayNode rows = MAPPER.createArrayNode();
        rows.add(grant(creatorIdentityId));
        return rows;
    }

    private ObjectNode grant(UUID creatorIdentityId) {
        ObjectNode row = MAPPER.createObjectNode();
        row.put("id", UUID.randomUUID().toString());
        row.put("creatorIdentityId", creatorIdentityId.toString());
        row.put("rights", "edit");
        return row;
    }
}
