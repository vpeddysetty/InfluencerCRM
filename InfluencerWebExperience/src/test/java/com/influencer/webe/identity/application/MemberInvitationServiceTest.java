package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.influencer.platform.workload.WorkloadTokenIssuer;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.shared.infrastructure.DaoHttpClientFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Instant;
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
 * Guards who may act on an invitation, and what resending does to the previous token.
 *
 * <p>The tenancy cases matter more than they look. An invitation id is the <em>only</em> thing the
 * revoke and resend endpoints take, so without an ownership check the id alone is the authorization
 * — which is what it was before these tests existed.
 */
class MemberInvitationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
    private static final UUID INVITATION = UUID.randomUUID();

    @Test
    @DisplayName("an invitation in another account cannot be revoked")
    void revokeRefusesAnotherAccountsInvitation() {
        // Holding member:remove on your own account is not a licence to reach into someone else's.
        var dao = new RecordingDao(OTHER_ACCOUNT);
        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).revoke(ACCOUNT, INVITATION));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertTrue(dao.writes.isEmpty(), "nothing may be written for an invitation you do not own");
    }

    @Test
    @DisplayName("an invitation in another account cannot be resent")
    void resendRefusesAnotherAccountsInvitation() {
        var dao = new RecordingDao(OTHER_ACCOUNT);
        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).resend(ACCOUNT, INVITATION, "admin@x.com"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertTrue(dao.writes.isEmpty());
    }

    @Test
    @DisplayName("a foreign invitation reads as missing, not as forbidden")
    void aForeignInvitationIsIndistinguishableFromAMissingOne() {
        // 403 would confirm that this id exists in some other account. The id is all an attacker
        // would have, so the answer must not tell them their guess was right.
        var foreign = assertThrows(ResponseStatusException.class,
                () -> service(new RecordingDao(OTHER_ACCOUNT)).revoke(ACCOUNT, INVITATION));
        var missing = assertThrows(ResponseStatusException.class,
                () -> service(new RecordingDao(null)).revoke(ACCOUNT, INVITATION));

        assertEquals(missing.getStatusCode(), foreign.getStatusCode());
        assertEquals(missing.getReason(), foreign.getReason());
    }

    @Test
    @DisplayName("resending replaces the stored hash, so the previous link stops working")
    void resendInvalidatesThePreviousToken() {
        // Two live tokens for one invitation would mean revoking the visible one still lets the
        // other in — the whole reason the pending-invitation index is unique.
        var dao = new RecordingDao(ACCOUNT);
        var first = service(dao).resend(ACCOUNT, INVITATION, "admin@x.com");
        var second = service(dao).resend(ACCOUNT, INVITATION, "admin@x.com");

        assertEquals(2, dao.writes.size());
        String firstHash = dao.writes.get(0).path("tokenHash").asText();
        String secondHash = dao.writes.get(1).path("tokenHash").asText();
        assertNotEquals(firstHash, secondHash, "each resend must store a different hash");
        assertNotEquals(first.token(), second.token());
    }

    @Test
    @DisplayName("resending rotates in place rather than creating a new invitation")
    void resendKeepsTheSameInvitation() {
        // Revoke-and-recreate would change the id, so the row an admin clicked disappears and a
        // different one appears — and every resend would leave a revoked row in the audit trail.
        var dao = new RecordingDao(ACCOUNT);
        service(dao).resend(ACCOUNT, INVITATION, "admin@x.com");

        assertTrue(dao.writtenPaths.get(0).endsWith("/rotate-token"), dao.writtenPaths.get(0));
        assertTrue(dao.writtenPaths.stream().noneMatch(p -> p.endsWith("/revoke")),
                "a resend is not a revocation: " + dao.writtenPaths);
    }

    @Test
    @DisplayName("the raw token never reaches the database")
    void onlyTheHashIsStored() {
        var dao = new RecordingDao(ACCOUNT);
        var resent = service(dao).resend(ACCOUNT, INVITATION, "admin@x.com");

        String stored = dao.writes.get(0).path("tokenHash").asText();
        assertNotEquals(resent.token(), stored);
        assertFalse(stored.contains(resent.token()));
    }

    @Test
    @DisplayName("a resend under the log provider reports that nothing was delivered")
    void resendIsHonestAboutDelivery() {
        var resent = service(new RecordingDao(ACCOUNT)).resend(ACCOUNT, INVITATION, "admin@x.com");

        assertFalse(resent.emailDelivered(),
                "nothing left the building, and the caller must not be told otherwise");
    }

    @Test
    @DisplayName("an unreadable invitation list yields no count rather than zero")
    void pendingInvitationsFailsToEmptyNotZero() {
        // Returning 0 here would hand a batch the wrong answer silently. Empty lets each caller
        // choose: the single invite carries on, a batch refuses.
        var dao = new RecordingDao(ACCOUNT);
        dao.failInvitationsRead = true;

        assertTrue(service(dao).pendingInvitations(ACCOUNT).isEmpty());
    }

    @Test
    @DisplayName("only live pending invitations hold a seat")
    void expiredAndSettledInvitationsAreNotCounted() {
        // An accepted invitation is already a member and would be charged twice; an expired one
        // cannot be redeemed, so holding its seat shrinks the account permanently.
        ArrayNode rows = MAPPER.createArrayNode();
        addInvitation(rows, "live@x.com", "pending", Instant.now().plusSeconds(86_400));
        addInvitation(rows, "gone@x.com", "pending", Instant.now().minusSeconds(60));
        addInvitation(rows, "in@x.com", "accepted", Instant.now().plusSeconds(86_400));
        addInvitation(rows, "dropped@x.com", "revoked", Instant.now().plusSeconds(86_400));

        var dao = new RecordingDao(ACCOUNT);
        dao.invitations = rows;
        var pending = service(dao).pendingInvitations(ACCOUNT).orElseThrow();

        assertEquals(1, pending.count());
        assertTrue(pending.contains("live@x.com"));
        assertFalse(pending.contains("gone@x.com"));
    }

    @Test
    @DisplayName("a pending address is recognised however it is capitalised")
    void pendingAddressesAreMatchedCaseInsensitively() {
        // The column is citext, so a caller testing "Bob@X.com" against a stored "bob@x.com" must
        // not conclude the address is free.
        ArrayNode rows = MAPPER.createArrayNode();
        addInvitation(rows, "Bob@X.com", "pending", Instant.now().plusSeconds(86_400));

        var dao = new RecordingDao(ACCOUNT);
        dao.invitations = rows;
        var pending = service(dao).pendingInvitations(ACCOUNT).orElseThrow();

        assertTrue(pending.contains("bob@x.com"));
        assertTrue(pending.contains("BOB@X.COM"));
    }

    // ------------------------------------------------------------------ fixtures

    private static MemberInvitationService service(RecordingDao dao) {
        return new MemberInvitationService(
                new DaoTenancyClient(dao), null, new LoggingEmailPort(), "https://ui.test");
    }

    private static void addInvitation(ArrayNode rows, String email, String status, Instant expiresAt) {
        rows.addObject()
                .put("id", UUID.randomUUID().toString())
                .put("email", email)
                .put("status", status)
                .put("expiresAt", expiresAt.toString());
    }

    private static final class LoggingEmailPort implements EmailPort {
        @Override
        public Result send(Message message) {
            return new Result(true, "log", "logged");
        }

        @Override
        public String provider() {
            return "log";
        }
    }

    /**
     * A DAO whose invitation belongs to {@code owner} — or to nobody, when {@code owner} is null.
     */
    private static final class RecordingDao extends DaoGatewayClient {

        private final UUID owner;
        ArrayNode invitations = MAPPER.createArrayNode();
        boolean failInvitationsRead;
        final List<JsonNode> writes = new ArrayList<>();
        final List<String> writtenPaths = new ArrayList<>();

        RecordingDao(UUID owner) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, new WorkloadTokenIssuer("test", "", ""));
            this.owner = owner;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.endsWith("/invitations")) {
                if (failInvitationsRead) {
                    throw new IllegalStateException("DAO unreachable");
                }
                return invitations;
            }
            if (path.contains("/invitations/")) {
                if (owner == null) {
                    // A null body, not a thrown 404: the gateway returns null for an empty
                    // response, and that path has to reach the same refusal.
                    return null;
                }
                return MAPPER.createObjectNode()
                        .put("id", INVITATION.toString())
                        .put("accountId", owner.toString())
                        .put("email", "invitee@x.com")
                        .put("role", "MARKETER")
                        .put("status", "pending");
            }
            return MAPPER.createObjectNode();
        }

        @Override
        public JsonNode post(String path, JsonNode payload) {
            writtenPaths.add(path);
            writes.add(payload);
            return MAPPER.createObjectNode()
                    .put("id", INVITATION.toString())
                    .put("accountId", String.valueOf(owner))
                    .put("email", "invitee@x.com")
                    .put("role", "MARKETER")
                    .put("status", "pending");
        }
    }
}
