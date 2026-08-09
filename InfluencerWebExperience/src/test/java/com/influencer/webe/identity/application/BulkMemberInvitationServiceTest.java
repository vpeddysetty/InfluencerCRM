package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.platform.workload.WorkloadTokenIssuer;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards what a batch does that a loop of single invites would get wrong.
 *
 * <p>Almost every case asserts the number of writes as well as the status. A batch that refuses
 * with the right code after having already created half its rows is the failure this whole design
 * exists to prevent, and a status assertion alone cannot see it.
 *
 * <p>Collaborators are subclassed rather than mocked, matching {@code EntitlementServiceTest}:
 * Mockito's bundled bytecode engine cannot mock these under Java 26, and {@code DaoGatewayClient}'s
 * constructor calls {@code factory.create()}. Nothing here opens a socket.
 */
class BulkMemberInvitationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    // ------------------------------------------------------------------ the pure decision

    @Test
    @DisplayName("the same address twice is invited once, however it is capitalised")
    void duplicatesCollapseCaseInsensitively() {
        // The column is citext, and the DAO revokes an existing pending invitation before inserting
        // a new one. Without this, row 2 would revoke row 1's token and the account would be
        // charged two seats for one live invitation.
        var decisions = BulkMemberInvitationService.decide(
                List.of(parsed(0, "bob@x.com"), parsed(1, "Bob@X.com"), parsed(2, "BOB@X.COM")),
                Set.of(), Set.of());

        assertEquals(List.of("invited", "skipped_duplicate", "skipped_duplicate"), keys(decisions));
        assertEquals("Duplicate of row 1 in this upload.", decisions.get(1).reason());
        assertEquals("Duplicate of row 1 in this upload.", decisions.get(2).reason());
    }

    @Test
    @DisplayName("a repeated address keeps the first row's role, not the broader one")
    void theFirstOccurrenceWins() throws Exception {
        // A file listing one person as MARKETER on row 1 and ADMIN on row 2 is a copy-paste
        // artifact far more often than a deliberate promotion. Taking the later, broader row would
        // silently grant more than the first row asked for.
        var dao = new RecordingDao(members(), invitations(), "pro");
        var result = service(dao).inviteAll(context(),
                List.of(row("bob@x.com", "MARKETER"), row("bob@x.com", "ADMIN")));

        assertEquals(1, result.invited());
        assertEquals("MARKETER", dao.created.get(0).path("role").asText());
    }

    @Test
    @DisplayName("someone already on the account is skipped, not failed and not invited")
    void alreadyMemberIsSkipped() {
        // Re-uploading last month's roster with three names appended is the normal way this feature
        // gets used. Everyone already inside must be a quiet skip, not a wall of red.
        var decisions = BulkMemberInvitationService.decide(
                List.of(parsed(0, "old@x.com"), parsed(1, "new@x.com")),
                Set.of("old@x.com"), Set.of());

        assertEquals(List.of("skipped_already_member", "invited"), keys(decisions));
        assertEquals("Already a member of this account.", decisions.get(0).reason());
    }

    @Test
    @DisplayName("someone already invited is skipped rather than re-issued")
    void alreadyInvitedIsSkipped() {
        // Re-sending would revoke the outstanding token. While nothing is being emailed that token
        // may be the only copy in existence, and the admin may already have forwarded it by hand.
        var decisions = BulkMemberInvitationService.decide(
                List.of(parsed(0, "waiting@x.com")), Set.of(), Set.of("waiting@x.com"));

        assertEquals(List.of("skipped_already_invited"), keys(decisions));
        assertTrue(decisions.get(0).reason().contains("resend"), decisions.get(0).reason());
    }

    @Test
    @DisplayName("outcomes come back in request order, skips included")
    void rowIndexesFollowTheRequest() {
        // The UI lines results up against the file the user uploaded by index. A re-sorted or
        // gap-filled list would attach the wrong reason to the wrong row.
        var decisions = BulkMemberInvitationService.decide(
                List.of(parsed(0, "a@x.com"), parsed(1, "dup@x.com"), parsed(2, "dup@x.com"),
                        parsed(3, "member@x.com"), parsed(4, "b@x.com")),
                Set.of("member@x.com"), Set.of());

        assertEquals(List.of(0, 1, 2, 3, 4), decisions.stream().map(d -> d.index()).toList());
        assertEquals(List.of("invited", "invited", "skipped_duplicate", "skipped_already_member", "invited"),
                keys(decisions));
    }

    // ------------------------------------------------------------------ whole-batch refusals

    @Test
    @DisplayName("an OWNER row refuses the batch before anything is written")
    void ownerRefusesTheWholeBatch() {
        // Ownership carries billing and the right to delete the account. The single-invite path
        // refuses it too — but there, refusing after the fact is harmless. Here it has to happen
        // before the other rows are created, or a batch half-succeeds on its way to an error.
        var dao = new RecordingDao(members(), invitations(), "agency");
        var error = assertThrows(ResponseStatusException.class, () -> service(dao).inviteAll(context(),
                List.of(row("fine@x.com", "MARKETER"), row("boss@x.com", "OWNER"))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("row 2"), error.getReason());
        assertEquals(0, dao.created.size(), "nothing may be written when a row is refused");
    }

    @Test
    @DisplayName("every unreadable row is named, not just the first")
    void allBadRowsAreReported() {
        // Fixing a file one error per upload is how people give up on a bulk feature.
        var dao = new RecordingDao(members(), invitations(), "agency");
        var error = assertThrows(ResponseStatusException.class, () -> service(dao).inviteAll(context(),
                List.of(row("ok@x.com", "MARKETER"), row("not-an-email", "MARKETER"),
                        row("also@x.com", "EDITOR"))));

        String reason = error.getReason();
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(reason.contains("row 2"), reason);
        assertTrue(reason.contains("row 3"), reason);
        assertTrue(reason.contains("EDITOR"), reason);
        assertTrue(reason.contains("Nothing was sent"), reason);
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("an empty batch and an oversized batch are both refused")
    void batchSizeIsBounded() {
        var dao = new RecordingDao(members(), invitations(), "agency");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), List.of())).getStatusCode());

        List<BulkMemberInvitationService.InviteRow> tooMany = new ArrayList<>();
        for (int i = 0; i <= BulkMemberInvitationService.MAX_BATCH; i++) {
            tooMany.add(row("person" + i + "@x.com", "MARKETER"));
        }
        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), tooMany));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("50"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("a batch past the seat limit is refused whole, with nothing written")
    void overCapacityRefusesEverything() {
        // Partially filling would leave the admin unable to tell who got in without reading the
        // results row by row, and re-uploading to catch the rest would re-send the ones that worked.
        var dao = new RecordingDao(members("a@x.com", "b@x.com", "c@x.com"), invitations(), "pro");
        var error = assertThrows(ResponseStatusException.class, () -> service(dao).inviteAll(context(),
                rows(8)));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
        assertTrue(error.getReason().contains("Nothing was sent"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("a batch that lands exactly on the limit is allowed")
    void exactlyFillingThePlanIsAllowed() {
        // Pro allows 10. Three members plus a batch of seven is exactly ten — the batch an admin
        // sizes deliberately after reading "7 seats available" on the same screen.
        var dao = new RecordingDao(members("a@x.com", "b@x.com", "c@x.com"), invitations(), "pro");
        var result = service(dao).inviteAll(context(), rows(7));

        assertEquals(7, result.invited());
        assertEquals(0, result.seatsRemaining());
    }

    @Test
    @DisplayName("pending invitations hold seats against the batch")
    void pendingInvitationsCountAgainstCapacity() {
        // Counting members alone would let an at-capacity account send invitations that all fail on
        // acceptance — the invitee hits the wall having done nothing wrong.
        var dao = new RecordingDao(members("a@x.com"), invitations("waiting@x.com"), "free");
        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), rows(1)));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("skipped rows do not consume seats")
    void skipsAreFree() {
        // Nine of ten seats used, and a batch of five where four are already members. Only one row
        // needs a seat, so the batch must go through — charging for skips would refuse a re-upload
        // that asks for almost nothing.
        var dao = new RecordingDao(
                members("m1@x.com", "m2@x.com", "m3@x.com", "m4@x.com",
                        "m5@x.com", "m6@x.com", "m7@x.com", "m8@x.com", "m9@x.com"),
                invitations(), "pro");
        var result = service(dao).inviteAll(context(), List.of(
                row("m1@x.com", "MARKETER"), row("m2@x.com", "MARKETER"),
                row("m3@x.com", "MARKETER"), row("m4@x.com", "MARKETER"),
                row("fresh@x.com", "MARKETER")));

        assertEquals(1, result.invited());
        assertEquals(4, result.skipped());
        assertEquals(1, dao.created.size());
    }

    @Test
    @DisplayName("assigning a role on the free tier is refused as a plan limit, not a permission")
    void roleAssignmentIsThePaidFeature() {
        // Explicit rather than inferred. Free is blocked from role assignment today only because it
        // has one seat — a coincidence that stops holding the moment that number changes.
        var dao = new RecordingDao(members(), invitations(), "free");
        var error = assertThrows(ResponseStatusException.class, () -> service(dao).inviteAll(context(),
                List.of(row("someone@x.com", "ADMIN"))));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
        assertTrue(error.getReason().contains("Team roles"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("an all-default batch is judged on seats, not on the role feature")
    void theTwoPaymentRefusalsStayDistinguishable() {
        // Both are 402. If a MARKETER-only batch tripped the role gate, an admin would be told to
        // buy roles when what they actually ran out of was seats.
        var dao = new RecordingDao(members("a@x.com"), invitations(), "free");
        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), rows(1)));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, error.getStatusCode());
        assertFalse(error.getReason().contains("Team roles"),
                "a marketer-only batch ran out of seats, not of the roles feature: " + error.getReason());
    }

    @Test
    @DisplayName("a brand the caller cannot reach refuses the batch")
    void unreachableBrandRefusesTheBatch() {
        var dao = new RecordingDao(members(), invitations(), "agency");
        var error = assertThrows(ResponseStatusException.class, () -> service(dao).inviteAll(context(),
                List.of(new BulkMemberInvitationService.InviteRow("a@x.com", "MARKETER", OTHER_BRAND))));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(error.getReason().contains("row 1"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    // ------------------------------------------------------------------ fail-closed reads

    @Test
    @DisplayName("an unreadable invitation list refuses the batch rather than assuming none")
    void unreadablePendingCountFailsClosed() {
        // The single-invite path deliberately fails OPEN here, over-granting by at most one seat.
        // The same trade on a batch over-grants by the whole batch, so this one refuses.
        var dao = new RecordingDao(members(), invitations(), "agency");
        dao.failInvitationsRead = true;

        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), rows(3)));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertTrue(error.getReason().contains("Nothing was created"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("an unreadable member list refuses the batch")
    void unreadableMemberListFailsClosed() {
        var dao = new RecordingDao(members(), invitations(), "agency");
        dao.failMembersRead = true;

        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), rows(3)));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertEquals(0, dao.created.size());
    }

    @Test
    @DisplayName("a member with no address on record refuses the batch")
    void memberWithoutAnEmailFailsClosed() {
        // That member cannot be matched against the upload, so an invitation would be created for
        // someone already inside — the exact state the already-member check exists to prevent,
        // reached silently.
        ArrayNode incomplete = MAPPER.createArrayNode();
        incomplete.addObject().put("userId", UUID.randomUUID().toString()).putNull("email");
        var dao = new RecordingDao(incomplete, invitations(), "agency");

        var error = assertThrows(ResponseStatusException.class,
                () -> service(dao).inviteAll(context(), rows(2)));

        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
        assertTrue(error.getReason().contains("no email address"), error.getReason());
        assertEquals(0, dao.created.size());
    }

    // ------------------------------------------------------------------ the write loop

    @Test
    @DisplayName("one failing row does not abort the rest")
    void oneFailureDoesNotStopTheBatch() {
        // Aborting would strand the rows already written with no record of which they were.
        var dao = new RecordingDao(members(), invitations(), "agency");
        dao.failCreateFor = "bad@x.com";

        var result = service(dao).inviteAll(context(), List.of(
                row("a@x.com", "MARKETER"), row("bad@x.com", "MARKETER"), row("c@x.com", "MARKETER")));

        assertEquals(3, result.requested());
        assertEquals(2, result.invited());
        assertEquals(1, result.failed());
        assertEquals("failed", result.rows().get(1).outcome());
    }

    @Test
    @DisplayName("a row failure reports nothing internal")
    void failureReasonsCarryNoInternals() {
        // A gateway failure's message carries the DAO's URL and its raw response body. That belongs
        // in the log, not in a browser.
        var dao = new RecordingDao(members(), invitations(), "agency");
        dao.failCreateFor = "bad@x.com";

        var result = service(dao).inviteAll(context(), List.of(row("bad@x.com", "MARKETER")));

        String reason = result.rows().get(0).reason();
        assertNotNull(reason);
        assertFalse(reason.contains("http"), reason);
        assertFalse(reason.contains("/tenancy"), reason);
    }

    @Test
    @DisplayName("the response carries no invitation token anywhere")
    void noTokenIsEverReturned() throws Exception {
        // The single assertion that keeps the design decision true through future refactors. Fifty
        // tokens in one body would be fifty live credentials in the browser, in any screenshot of
        // the results table, and in any error report that captured the response.
        var dao = new RecordingDao(members(), invitations(), "agency");
        var result = service(dao).inviteAll(context(), rows(3));

        String json = MAPPER.writeValueAsString(result);
        assertFalse(json.contains("token"), "no token field may exist: " + json);
        assertFalse(json.matches(".*[A-Za-z0-9_-]{43}.*"),
                "no 43-character token may appear in the body: " + json);
    }

    @Test
    @DisplayName("the whole batch reports honestly that nothing was emailed")
    void deliveryIsReportedHonestly() {
        // With the log-only provider nothing leaves the building. A screen that says "invitations
        // sent" then leaves an admin waiting for replies that cannot come.
        var dao = new RecordingDao(members(), invitations(), "agency");
        var result = service(dao).inviteAll(context(), rows(2));

        assertFalse(result.emailDelivered(),
                "the log provider delivers nothing and the result must say so");
    }

    @Test
    @DisplayName("remaining seats are reported after the batch, unlimited as the sentinel")
    void seatsRemainingIsReported() {
        var pro = new RecordingDao(members("a@x.com"), invitations(), "pro");
        assertEquals(6, service(pro).inviteAll(context(), rows(3)).seatsRemaining());

        var agency = new RecordingDao(members(), invitations(), "agency");
        assertEquals(PlanPolicy.UNLIMITED, service(agency).inviteAll(context(), rows(3)).seatsRemaining());
    }

    @Test
    @DisplayName("a row without a role is created as the default, not refused")
    void aMissingRoleTakesTheDefault() {
        var dao = new RecordingDao(members(), invitations(), "agency");
        var result = service(dao).inviteAll(context(),
                List.of(new BulkMemberInvitationService.InviteRow("a@x.com", null, null)));

        assertEquals(1, result.invited());
        assertEquals("MARKETER", dao.created.get(0).path("role").asText());
    }

    @Test
    @DisplayName("blank rows are dropped rather than counted or refused")
    void blankRowsAreIgnored() {
        // A pasted list and a CSV both end with trailing newlines. Refusing the batch over one would
        // be an unhelpful reading of an obvious intent.
        var dao = new RecordingDao(members(), invitations(), "agency");
        var result = service(dao).inviteAll(context(), List.of(
                row("a@x.com", "MARKETER"),
                new BulkMemberInvitationService.InviteRow("   ", "MARKETER", null)));

        assertEquals(1, result.requested());
        assertEquals(1, result.invited());
    }

    // ------------------------------------------------------------------ fixtures

    private static BulkMemberInvitationService service(RecordingDao dao) {
        var tenancy = new DaoTenancyClient(dao);
        var invitations = new MemberInvitationService(tenancy, null, new LoggingEmailPort(), "https://ui.test");
        return new BulkMemberInvitationService(invitations, new EntitlementService(dao), tenancy);
    }

    private static TenantContext context() {
        return new TenantContext(USER, ACCOUNT, BRAND, "admin@x.com", AccountRole.ADMIN,
                Set.of(Permission.MEMBER_INVITE), Set.of(BRAND));
    }

    private static BulkMemberInvitationService.InviteRow row(String email, String role) {
        return new BulkMemberInvitationService.InviteRow(email, role, null);
    }

    private static List<BulkMemberInvitationService.InviteRow> rows(int count) {
        List<BulkMemberInvitationService.InviteRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row("person" + i + "@x.com", "MARKETER"));
        }
        return rows;
    }

    private static BulkMemberInvitationService.ParsedRow parsed(int index, String email) {
        return new BulkMemberInvitationService.ParsedRow(
                index, email.trim().toLowerCase(), AccountRole.MARKETER, null);
    }

    private static List<String> keys(List<BulkMemberInvitationService.Decision> decisions) {
        return decisions.stream().map(d -> d.outcome().key()).toList();
    }

    private static ArrayNode members(String... emails) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (String email : emails) {
            rows.addObject().put("userId", UUID.randomUUID().toString()).put("email", email);
        }
        return rows;
    }

    private static ArrayNode invitations(String... emails) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (String email : emails) {
            rows.addObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("email", email)
                    .put("status", "pending")
                    .put("expiresAt", Instant.now().plusSeconds(86_400).toString());
        }
        return rows;
    }

    /** Nothing is delivered, which is exactly the environment the feature has to be honest about. */
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
     * A DAO that answers from fixtures and records what was written.
     *
     * <p>The write log is the point: most of these tests assert that a refusal left it empty.
     */
    private static final class RecordingDao extends DaoGatewayClient {

        private final ArrayNode members;
        private final ArrayNode invitations;
        private final String plan;

        final List<JsonNode> created = new ArrayList<>();
        boolean failMembersRead;
        boolean failInvitationsRead;
        String failCreateFor;

        RecordingDao(ArrayNode members, ArrayNode invitations, String plan) {
            super(null, null, new DaoHttpClientFactory(null) {
                @Override
                public HttpClient create() {
                    return null;
                }
            }, new WorkloadTokenIssuer("test", "", ""));
            this.members = members;
            this.invitations = invitations;
            this.plan = plan;
        }

        @Override
        public JsonNode get(String path, Map<String, String> query) {
            if (path.endsWith("/members")) {
                if (failMembersRead) {
                    throw new IllegalStateException("DAO unreachable");
                }
                return members;
            }
            if (path.endsWith("/invitations")) {
                if (failInvitationsRead) {
                    throw new IllegalStateException("DAO unreachable");
                }
                return invitations;
            }
            if (path.endsWith("/brands")) {
                ArrayNode brands = MAPPER.createArrayNode();
                brands.addObject()
                        .put("brandId", BRAND.toString())
                        .put("brandName", "Test Brand")
                        .put("accountId", ACCOUNT.toString())
                        .put("accountType", "agency")
                        .put("effectiveRole", "ADMIN");
                return brands;
            }
            // The account lookup behind planFor.
            return MAPPER.createObjectNode().put("plan", plan);
        }

        @Override
        public JsonNode post(String path, JsonNode payload) {
            if (failCreateFor != null && failCreateFor.equals(payload.path("email").asText())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "DAO POST https://dao.internal/tenancy/... failed: {\"error\":\"boom\"}");
            }
            created.add(payload);
            ObjectNode response = MAPPER.createObjectNode();
            response.put("id", UUID.randomUUID().toString());
            response.set("email", payload.get("email"));
            return response;
        }
    }
}
