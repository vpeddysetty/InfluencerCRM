package com.influencer.webe.identity.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.infrastructure.DaoTenancyClient;
import com.influencer.webe.security.AccountRole;
import com.influencer.webe.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Invites many members at once, from a pasted list or an uploaded file.
 *
 * <p><b>Why this is not a loop over {@link MemberInvitationService#invite}.</b> Four things behave
 * differently in a batch, and each of them is wrong by default:
 *
 * <ul>
 *   <li><b>Duplicates destroy each other.</b> The DAO revokes an existing pending invitation for the
 *       same address before inserting a new one — two live tokens for one seat is the state it is
 *       avoiding. In a loop, the same address twice in one file means the second row revokes the
 *       first, leaving one live invitation where the seat arithmetic counted two.</li>
 *   <li><b>The capacity check is a read followed by a write.</b> Asking "may I create one more"
 *       twenty times gets twenty yeses against the same stale count. The batch has to ask once, for
 *       the number it actually intends to create.</li>
 *   <li><b>The pending-invitation count fails open.</b> Sound for one invitation, where the cost of
 *       a failed secondary read is at most one extra seat. For fifty it is fifty.</li>
 *   <li><b>Nothing checks whether the invitee is already a member.</b> Rare enough to ignore when a
 *       human types one address; the normal case when someone re-uploads last month's roster with
 *       three names added.</li>
 * </ul>
 *
 * <p><b>Validate everything, then write.</b> Nothing reaches the database until the whole batch is
 * known to be admissible. A partly-applied batch is the worst outcome available here: the admin
 * cannot tell who was invited without reading a results table line by line, and re-uploading to
 * catch the remainder re-sends the ones that worked.
 *
 * <p><b>The response carries no tokens.</b> Fifty invitation tokens in one JSON body would be fifty
 * live credentials in the browser's memory, in any screenshot of the results table, and in any error
 * report that captured the response. An invitation nobody was emailed is recovered through the
 * resend endpoint, one at a time and deliberately.
 *
 * <p><b>Known race, accepted.</b> Two bulk calls on one account can both pass the capacity gate
 * against the same count and then both write, over-committing seats by up to {@link #MAX_BATCH}.
 * Closing it needs a DAO endpoint that counts and inserts inside one transaction. It is tolerated
 * because it takes two admins acting within the same moment, the excess rows are pending, revocable
 * and visible on the same screen, and acceptance is not capacity-gated either — so this is a
 * property of the invitation model that a batch widens rather than one it introduces. The failure
 * mode is a revenue leak, not a security or integrity problem. Revisit when seats are billed per
 * unit.
 */
@Service
public class BulkMemberInvitationService {

    /**
     * The most invitations one request may carry.
     *
     * <p>Bounds three things at once: the work a single request can queue, the blast radius of the
     * non-atomic write loop, and how far the capacity race above can over-commit. Fifty is well
     * above the size of a team an agency onboards in one sitting, and the write loop at that size is
     * fifty cheap sequential DAO calls.
     */
    public static final int MAX_BATCH = 50;

    /** The role the DAO assigns when a row does not name one. */
    static final AccountRole DEFAULT_ROLE = AccountRole.MARKETER;

    private static final Logger log = LoggerFactory.getLogger(BulkMemberInvitationService.class);

    private final MemberInvitationService invitations;
    private final EntitlementService entitlements;
    private final DaoTenancyClient tenancyClient;

    public BulkMemberInvitationService(MemberInvitationService invitations,
                                       EntitlementService entitlements,
                                       DaoTenancyClient tenancyClient) {
        this.invitations = invitations;
        this.entitlements = entitlements;
        this.tenancyClient = tenancyClient;
    }

    // ------------------------------------------------------------------ the pure decision

    /**
     * Decides what each row becomes, without touching anything.
     *
     * <p>Package-private and static so the cases worth pinning down — a duplicate that arrives
     * before its original, a row for someone already on the account, a batch that lands exactly on
     * the plan limit — are testable as plain values. Everything above this method is I/O and
     * everything below it is arithmetic, which is the split that makes the arithmetic reviewable.
     *
     * @param rows          the requested rows, in the order the caller sent them
     * @param memberEmails  addresses already on the account, normalized lowercase
     * @param pendingEmails addresses with a live invitation, normalized lowercase
     */
    static List<Decision> decide(List<ParsedRow> rows, Set<String> memberEmails, Set<String> pendingEmails) {
        List<Decision> decisions = new ArrayList<>(rows.size());
        // Ordered so "duplicate of row 3" names the first occurrence rather than an arbitrary one.
        Map<String, Integer> firstSeen = new LinkedHashMap<>();

        for (ParsedRow row : rows) {
            String email = row.email();

            Integer earlier = firstSeen.get(email);
            if (earlier != null) {
                // The first occurrence wins, including its role. A file naming one address twice at
                // two different roles is far likelier to be a copy-paste artifact than a deliberate
                // escalation, and taking the later — possibly broader — row would be the dangerous
                // way to resolve it. Refusing the batch outright would be worse still: this is the
                // single most common defect in a hand-assembled list.
                decisions.add(new Decision(row.index(), Outcome.SKIPPED_DUPLICATE,
                        "Duplicate of row %d in this upload.".formatted(earlier + 1)));
                continue;
            }
            firstSeen.put(email, row.index());

            if (memberEmails.contains(email)) {
                // Skipped, not failed. Inviting someone already on the account is a no-op the user
                // meant well by, and a red row would put a problem in front of someone for whom
                // nothing is wrong. It does have to be stopped rather than sent: accepting such an
                // invitation runs upsertMembership, which would quietly reset the role they hold.
                decisions.add(new Decision(row.index(), Outcome.SKIPPED_ALREADY_MEMBER,
                        "Already a member of this account."));
                continue;
            }

            if (pendingEmails.contains(email)) {
                // Skipped rather than replaced. Sending again would revoke the outstanding token,
                // which — while nothing is being emailed — may be the only copy in existence, and
                // the admin may already have forwarded it by hand. Resending is a deliberate act
                // with its own endpoint, not a side effect of appearing in a second upload.
                decisions.add(new Decision(row.index(), Outcome.SKIPPED_ALREADY_INVITED,
                        "A pending invitation to this address already exists — resend it instead."));
                continue;
            }

            decisions.add(new Decision(row.index(), Outcome.INVITED, null));
        }
        return decisions;
    }

    // ------------------------------------------------------------------ the orchestration

    /**
     * Validates the whole batch, then creates what survived.
     *
     * @throws ResponseStatusException 400 on a batch that cannot be read, 402 on a limit, 502 when
     *                                 a check that must fail closed could not be made
     */
    public BulkInviteResult inviteAll(TenantContext context, List<InviteRow> requested) {
        List<InviteRow> rows = requested == null ? List.of() : requested.stream()
                .filter(row -> row != null && row.email() != null && !row.email().isBlank())
                .toList();

        requireReadableBatch(rows);
        List<ParsedRow> parsed = parseOrRefuse(rows);
        requireRoleAssignmentAllowed(context, parsed);

        // Fail closed, unlike the single-invite path: an unreadable count there costs at most one
        // seat, here it would cost the whole batch.
        MemberInvitationService.PendingInvitations pending =
                invitations.pendingInvitations(context.accountId())
                        .orElseThrow(() -> unavailable("check which invitations are already outstanding"));
        Set<String> memberEmails = memberEmailsOrRefuse(context);
        Map<UUID, String> brandNames = brandNamesOrRefuse(context, parsed);

        List<Decision> decisions = decide(parsed, memberEmails, pending.emails());

        long committed = memberEmails.size() + pending.count();
        long toCreate = decisions.stream().filter(Decision::willCreate).count();
        entitlements.requireCapacityFor(context.accountId(), PlanPolicy.Resource.MEMBER, committed, toCreate);

        return create(context, decisions, parsed, brandNames, committed);
    }

    /** Nothing has been read yet, so a batch that cannot be sized costs no DAO round trip. */
    private void requireReadableBatch(List<InviteRow> rows) {
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide at least one email address.");
        }
        if (rows.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ("Up to %d invitations per upload; this batch has %d. Split it into smaller "
                     + "uploads.").formatted(MAX_BATCH, rows.size()));
        }
    }

    /**
     * Parses every row, refusing the batch if any cannot be read.
     *
     * <p><b>Whole-batch, not per-row</b>, and deliberately unlike a duplicate. A malformed address
     * or an unknown role is an authoring mistake in the file that was just uploaded, and the user's
     * intent for that row is unknown — it should be corrected and re-uploaded, not half-applied
     * around. A duplicate is different in kind: what the user meant is obvious.
     *
     * <p>Every bad row is named, not just the first. Fixing a file one error per upload is the
     * behaviour that makes people give up on a bulk feature.
     */
    private List<ParsedRow> parseOrRefuse(List<InviteRow> rows) {
        List<ParsedRow> parsed = new ArrayList<>(rows.size());
        List<String> problems = new ArrayList<>();

        for (int index = 0; index < rows.size(); index++) {
            InviteRow row = rows.get(index);
            String email = normalize(row.email());
            int at = email.indexOf('@');
            // Deliberately not an RFC 5322 validator: the real test of an address is whether mail
            // to it arrives, and an over-strict pattern rejects valid addresses for no gain. This
            // catches what a spreadsheet column actually contains — a name, a header row, a phone
            // number.
            boolean looksLikeAddress = at > 0
                    && at == email.lastIndexOf('@')
                    && email.indexOf('.', at) > at + 1
                    && !email.endsWith(".")
                    && email.indexOf(' ') < 0;
            if (!looksLikeAddress) {
                problems.add("row %d (not an email address: '%s')".formatted(index + 1, row.email().trim()));
                continue;
            }

            String requestedRole = row.role() == null || row.role().isBlank()
                    ? DEFAULT_ROLE.name() : row.role().trim();
            Optional<AccountRole> role = AccountRole.parse(requestedRole);
            if (role.isEmpty()) {
                problems.add("row %d (unknown role: '%s')".formatted(index + 1, requestedRole));
                continue;
            }
            if (role.get() == AccountRole.OWNER) {
                // The same rule the single invite enforces, applied before anything is written so a
                // batch cannot get halfway through and then refuse. Ownership carries billing and
                // the right to delete the account; transferring it is a deliberate, separately
                // confirmed act rather than a column in a spreadsheet.
                problems.add(("row %d (OWNER cannot be granted by invitation — transfer ownership "
                              + "explicitly instead)").formatted(index + 1));
                continue;
            }

            parsed.add(new ParsedRow(index, email, role.get(), row.brandId()));
        }

        if (!problems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "%d %s could not be read: %s. Nothing was sent."
                            .formatted(problems.size(), problems.size() == 1 ? "row" : "rows",
                                    String.join(", ", problems)));
        }
        return parsed;
    }

    /**
     * Choosing what a teammate may do is the paid feature, so a batch that assigns roles is checked
     * for it — explicitly, rather than relying on the free tier's single seat to make role
     * assignment unreachable by coincidence.
     */
    private void requireRoleAssignmentAllowed(TenantContext context, List<ParsedRow> parsed) {
        boolean assignsRoles = parsed.stream().anyMatch(row -> row.role() != DEFAULT_ROLE);
        if (assignsRoles) {
            entitlements.requireRoleBasedAccess(context.accountId());
        }
    }

    /**
     * The addresses already on the account.
     *
     * <p>One read, used for both the already-member test and the seat count, so the two cannot
     * disagree. Refuses the batch if any member has no address on record: that member could not be
     * matched against the upload, so an invitation would be created for someone already inside —
     * precisely the state the check exists to prevent, arrived at silently.
     */
    private Set<String> memberEmailsOrRefuse(TenantContext context) {
        JsonNode members;
        try {
            members = tenancyClient.members(context.accountId());
        } catch (RuntimeException e) {
            log.warn("Could not read members for account {}: {}", context.accountId(), e.toString());
            throw unavailable("check who is already a member");
        }
        if (members == null || !members.isArray()) {
            throw unavailable("check who is already a member");
        }

        Set<String> emails = new HashSet<>();
        for (JsonNode member : members) {
            String email = normalize(member.path("email").asText(""));
            if (email.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "The member list is incomplete — a member has no email address on record, so "
                        + "people already on the account could not be identified. Nothing was sent.");
            }
            emails.add(email);
        }
        return emails;
    }

    /**
     * Resolves every brand named in the batch, in one read.
     *
     * <p>Doubles as the check that the caller may reach them: a brandId absent from the caller's own
     * accessible list is either a typo or an attempt to invite into someone else's brand, and both
     * deserve the same refusal. Also supplies the display name for the invitation email, replacing
     * what would otherwise be one lookup per row.
     */
    private Map<UUID, String> brandNamesOrRefuse(TenantContext context, List<ParsedRow> parsed) {
        Map<UUID, String> names = new HashMap<>();
        try {
            tenancyClient.findAccessibleBrands(context.userId()).stream()
                    .filter(brand -> brand.accountId() != null && brand.accountId().equals(context.accountId()))
                    .forEach(brand -> names.put(brand.brandId(), brand.brandName()));
        } catch (RuntimeException e) {
            log.warn("Could not read brands for user {}: {}", context.userId(), e.toString());
            throw unavailable("check which brands you can invite into");
        }

        List<String> unreachable = parsed.stream()
                .filter(row -> row.brandId() != null && !names.containsKey(row.brandId()))
                .map(row -> "row %d".formatted(row.index() + 1))
                .toList();
        if (!unreachable.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "%s names a brand you cannot invite into. Nothing was sent."
                            .formatted(String.join(", ", unreachable)));
        }
        return names;
    }

    /**
     * Creates the invitations that survived validation.
     *
     * <p>Sequential rather than parallel: the writes are cheap, ordering keeps the log readable, and
     * fanning fifty concurrent requests at the DAO from a request thread is a load-shaping decision
     * that should be made on purpose rather than smuggled in with a feature.
     *
     * <p>A row that fails is recorded and the loop continues. Aborting would strand the rows already
     * written with no record of which they were.
     */
    private BulkInviteResult create(TenantContext context, List<Decision> decisions,
                                    List<ParsedRow> parsed, Map<UUID, String> brandNames, long committed) {
        Map<Integer, ParsedRow> byIndex = new HashMap<>();
        parsed.forEach(row -> byIndex.put(row.index(), row));

        List<RowOutcome> outcomes = new ArrayList<>(decisions.size());
        int invited = 0;
        int skipped = 0;
        int failed = 0;
        boolean anyDelivered = false;

        for (Decision decision : decisions) {
            ParsedRow row = byIndex.get(decision.index());
            if (!decision.willCreate()) {
                skipped++;
                outcomes.add(new RowOutcome(decision.index(), row.email(), row.role().name(),
                        row.brandId(), decision.outcome().key(), decision.reason(), null));
                continue;
            }

            try {
                UUID effectiveBrand = row.brandId() != null ? row.brandId() : context.brandId();
                var created = invitations.invite(
                        context.accountId(), context.userId(), row.email(), row.role().name(),
                        row.brandId(), brandNames.get(effectiveBrand), context.email());
                anyDelivered |= created.emailDelivered();
                invited++;
                outcomes.add(new RowOutcome(decision.index(), row.email(), row.role().name(),
                        row.brandId(), Outcome.INVITED.key(), null, invitationId(created.invitation())));
            } catch (RuntimeException e) {
                // The full cause goes to the log, never to the response: a gateway failure carries
                // the DAO's URL and its raw body, which is not something to render in a browser.
                log.warn("Bulk invite row {} failed for account {}: {}",
                        decision.index(), context.accountId(), e.toString());
                failed++;
                outcomes.add(new RowOutcome(decision.index(), row.email(), row.role().name(),
                        row.brandId(), Outcome.FAILED.key(), shortCause(e), null));
            }
        }

        long remaining = entitlements.remainingCapacity(
                context.accountId(), PlanPolicy.Resource.MEMBER, committed + invited);

        return new BulkInviteResult(decisions.size(), invited, skipped, failed,
                remaining, anyDelivered, outcomes);
    }

    private static UUID invitationId(JsonNode invitation) {
        String id = invitation == null ? null : invitation.path("id").asText(null);
        return id == null || id.isBlank() ? null : UUID.fromString(id);
    }

    /** What a row failure is worth telling the user, with nothing internal in it. */
    private static String shortCause(RuntimeException e) {
        if (e instanceof ResponseStatusException status) {
            int code = status.getStatusCode().value();
            if (code == HttpStatus.CONFLICT.value()) {
                return "A conflicting invitation already exists.";
            }
            if (code >= 500) {
                return "The service was briefly unavailable — try this address again.";
            }
        }
        return "The invitation could not be created.";
    }

    /**
     * 502 rather than 500: the check itself is fine, the service it depends on did not answer. The
     * message says nothing was created, because that is the first thing an admin needs to know
     * before deciding whether to retry.
     */
    private static ResponseStatusException unavailable(String what) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Could not %s, so this batch was not sent. Nothing was created — try again in a moment."
                        .formatted(what));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    // ------------------------------------------------------------------ types

    /** One requested invitation, as the caller sent it. */
    public record InviteRow(String email, String role, UUID brandId) {
    }

    /** A row that parsed, with its position kept so results line up against the uploaded file. */
    record ParsedRow(int index, String email, AccountRole role, UUID brandId) {
    }

    /** What {@link #decide} concluded about one row. */
    record Decision(int index, Outcome outcome, String reason) {

        boolean willCreate() {
            return outcome == Outcome.INVITED;
        }
    }

    /** What became of a row. Serialized as the lowercase key, which is what the UI switches on. */
    enum Outcome {
        INVITED("invited"),
        SKIPPED_DUPLICATE("skipped_duplicate"),
        SKIPPED_ALREADY_MEMBER("skipped_already_member"),
        SKIPPED_ALREADY_INVITED("skipped_already_invited"),
        FAILED("failed");

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /**
     * The outcome of one batch.
     *
     * <p><b>No token field, and there must never be one.</b> See the class javadoc.
     *
     * @param requested      rows considered, after blank lines were dropped
     * @param seatsRemaining seats left after this batch; {@link PlanPolicy#UNLIMITED} on agency
     * @param emailDelivered whether any invitation was actually delivered. False for the whole batch
     *                       when the provider is {@code log} — a screen that says "invitations sent"
     *                       in that case is lying to someone who will then wait for replies
     */
    public record BulkInviteResult(int requested, int invited, int skipped, int failed,
                                   long seatsRemaining, boolean emailDelivered,
                                   List<RowOutcome> rows) {
    }

    /**
     * @param index   position in the request, so a results table can line up against the file the
     *                user uploaded rather than against a re-sorted list
     * @param outcome one of {@link Outcome}'s keys
     * @param reason  always present for anything other than {@code invited}
     */
    public record RowOutcome(int index, String email, String role, UUID brandId,
                             String outcome, String reason, UUID invitationId) {
    }
}
