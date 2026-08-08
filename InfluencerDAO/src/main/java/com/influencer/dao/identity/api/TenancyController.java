package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.Account;
import com.influencer.dao.identity.domain.Brand;
import com.influencer.dao.identity.domain.MemberInvitation;
import com.influencer.dao.identity.domain.Membership;
import com.influencer.dao.identity.domain.User;
import com.influencer.dao.identity.infrastructure.AccountRepository;
import com.influencer.dao.identity.infrastructure.BrandAccessRepository;
import com.influencer.dao.identity.infrastructure.BrandRepository;
import com.influencer.dao.identity.infrastructure.MemberInvitationRepository;
import com.influencer.dao.identity.infrastructure.MembershipRepository;
import com.influencer.dao.identity.infrastructure.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes the tenancy spine (accounts / brands / memberships).
 *
 * <p>The BFF calls {@code /tenancy/users/{userId}/brands} on every login to build the JWT's brand
 * claims, so this is the authority for "which brands may this user reach".
 */
@RestController
@RequestMapping("/tenancy")
public class TenancyController {

    /** Mirrors the {@code accounts_account_type_check} constraint. */
    private static final Set<String> ACCOUNT_TYPES = Set.of("brand", "agency");

    /**
     * Roles that reach every brand in their account without a {@code brand_access} row.
     *
     * <p>Must stay in step with the same list in {@code BrandRepository.findAccessibleBrands} and
     * with {@code AccountRole.impliesAllBrands()} in the BFF. If they disagree, a user is granted
     * access one way and refused the other.
     */
    private static final Set<String> ACCOUNT_WIDE_ROLES = Set.of("OWNER", "ADMIN", "FINANCE");

    private final BrandRepository brandRepository;
    private final AccountRepository accountRepository;
    private final MembershipRepository membershipRepository;
    private final MemberInvitationRepository invitationRepository;
    private final BrandAccessRepository brandAccessRepository;
    private final UserRepository userRepository;

    public TenancyController(BrandRepository brandRepository,
                             AccountRepository accountRepository,
                             MembershipRepository membershipRepository,
                             MemberInvitationRepository invitationRepository,
                             BrandAccessRepository brandAccessRepository,
                             UserRepository userRepository) {
        this.brandRepository = brandRepository;
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
        this.invitationRepository = invitationRepository;
        this.brandAccessRepository = brandAccessRepository;
        this.userRepository = userRepository;
    }

    /** Every brand this user may reach, with the role held on each. */
    @GetMapping("/users/{userId}/brands")
    public List<BrandAccessResponse> accessibleBrands(@PathVariable UUID userId) {
        return brandRepository.findAccessibleBrands(userId).stream()
                .map(row -> new BrandAccessResponse(
                        row.getBrandId(),
                        row.getBrandName(),
                        row.getAccountId(),
                        row.getAccountType(),
                        row.getAccountRole(),
                        row.getEffectiveRole()))
                .toList();
    }

    @GetMapping("/brands/{id}")
    public Brand findBrand(@PathVariable UUID id) {
        return brandRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brand not found"));
    }

    @GetMapping("/accounts/{accountId}/brands")
    public List<Brand> brandsForAccount(@PathVariable UUID accountId) {
        return brandRepository.findByAccountIdOrderByNameAsc(accountId);
    }

    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    public Brand createBrand(@RequestBody Brand brand) {
        if (brand.getAccountId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        if (brand.getName() == null || brand.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        accountRepository.findById(brand.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account not found"));

        brand.setId(null);
        // A brand created through this endpoint belongs to a real account, not to the Phase 1
        // backfill, so it must not claim a legacy user - that would corrupt the bridge trigger's
        // user_id -> brand_id mapping.
        brand.setLegacyUserId(null);
        brand.setCreatedAt(Instant.now());
        brand.setUpdatedAt(Instant.now());
        return brandRepository.save(brand);
    }

    @PutMapping("/brands/{id}")
    public Brand updateBrand(@PathVariable UUID id, @RequestBody Brand brand) {
        Brand existing = brandRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brand not found"));
        if (brand.getName() != null && !brand.getName().isBlank()) {
            existing.setName(brand.getName());
        }
        if (brand.getStatus() != null && !brand.getStatus().isBlank()) {
            existing.setStatus(brand.getStatus());
        }
        if (brand.getCustomAttributes() != null) {
            existing.setCustomAttributes(brand.getCustomAttributes());
        }
        existing.setUpdatedAt(Instant.now());
        return brandRepository.save(existing);
    }

    /**
     * Provisions a workspace for a new user: account, first brand, and membership.
     *
     * <p>This replaces the {@code provision_tenancy_for_user} trigger (roadmap Stage 2). It lives
     * behind one endpoint rather than three calls from the BFF so the three writes share a
     * transaction — a user with an account but no brand, or no membership, is a broken tenant that
     * every downstream query would then have to defend against.
     *
     * <p>Idempotent on {@code legacyUserId}: a retry after a partial failure, or a signup replayed
     * at-least-once, returns the existing workspace rather than creating a second one.
     */
    @PostMapping("/provision")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ProvisionResponse provision(@RequestBody ProvisionRequest request) {
        if (request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        String accountType = request.accountType() == null || request.accountType().isBlank()
                ? "brand"
                : request.accountType().trim().toLowerCase();
        if (!ACCOUNT_TYPES.contains(accountType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "accountType must be one of " + ACCOUNT_TYPES);
        }

        Optional<Account> existing = accountRepository.findByLegacyUserId(request.userId());
        if (existing.isPresent()) {
            Account account = existing.get();
            Brand brand = brandRepository.findByAccountIdOrderByNameAsc(account.getId()).stream()
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "Account exists for this user but has no brand"));
            return new ProvisionResponse(account.getId(), brand.getId(), account.getAccountType(), request.role());
        }

        String workspaceName = request.workspaceName() == null || request.workspaceName().isBlank()
                ? "Workspace"
                : request.workspaceName().trim();
        String role = request.role() == null || request.role().isBlank() ? "OWNER" : request.role().trim();
        Instant now = Instant.now();

        Account account = new Account();
        account.setName(workspaceName);
        account.setAccountType(accountType);
        account.setPlan(request.plan() == null || request.plan().isBlank() ? "free" : request.plan());
        // legacy_user_id is what makes this idempotent and what resolves "the account for this
        // user" before a membership exists. It is a unique index, so a concurrent duplicate
        // signup fails on the constraint rather than producing two workspaces.
        account.setLegacyUserId(request.userId());
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        Account savedAccount = accountRepository.save(account);

        Brand brand = new Brand();
        brand.setAccountId(savedAccount.getId());
        brand.setName(workspaceName);
        brand.setLegacyUserId(request.userId());
        brand.setCreatedAt(now);
        brand.setUpdatedAt(now);
        Brand savedBrand = brandRepository.save(brand);

        membershipRepository.upsertMembership(savedAccount.getId(), request.userId(), role);

        return new ProvisionResponse(savedAccount.getId(), savedBrand.getId(), savedAccount.getAccountType(), role);
    }

    /**
     * The account provisioned for a user, resolved through {@code legacy_user_id}.
     *
     * <p>Immediately after signup the user record carries no account id — the trigger writes the
     * link the other way round. This is how the BFF finds the account it has just caused to exist.
     */
    @GetMapping("/users/{userId}/account")
    public AccountResponse accountForUser(@PathVariable UUID userId) {
        Account account = accountRepository.findByLegacyUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account for user"));
        return new AccountResponse(account.getId(), account.getName(), account.getAccountType(), account.getPlan());
    }

    /**
     * An account by id, including its plan.
     *
     * <p>Added for M2.3: entitlement checks resolve an account id from the request's verified JWT
     * claim and need the CURRENT plan for it. Reading the plan live on each check — rather than
     * carrying it in the token — is what makes an upgrade take effect immediately. A plan baked
     * into a JWT would leave a customer who has just paid still blocked until their token expired.
     */
    @GetMapping("/accounts/{id}")
    public AccountResponse account(@PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        return new AccountResponse(account.getId(), account.getName(), account.getAccountType(), account.getPlan());
    }

    /**
     * Sets an account's type, name or plan.
     *
     * <p>Exists because provisioning still happens in the {@code provision_tenancy_for_user}
     * trigger, which can only create a {@code brand} account. An agency signup therefore creates
     * the account and then promotes it, within the same signup call. When provisioning moves into
     * the application (roadmap Stage 2) the type is chosen at creation and this becomes an
     * administrative operation rather than part of signup.
     *
     * <p>The type is validated against the same two values as the database check constraint, so a
     * bad value is a 400 here rather than a constraint violation surfacing as a 500.
     *
     * <p><b>{@code plan} is settable here (M2.3)</b> so a billing integration has somewhere to
     * write an upgrade. It is deliberately NOT validated against the known plan names: this
     * endpoint should not need redeploying to introduce a plan, and {@code PlanPolicy} in the BFF
     * already fails closed on anything it does not recognise, so an unknown value grants the free
     * tier rather than everything.
     */
    @PatchMapping("/accounts/{id}")
    public AccountResponse updateAccount(@PathVariable UUID id, @RequestBody AccountPatch patch) {
        Account existing = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        if (patch.accountType() != null && !patch.accountType().isBlank()) {
            String requested = patch.accountType().trim().toLowerCase();
            if (!ACCOUNT_TYPES.contains(requested)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "accountType must be one of " + ACCOUNT_TYPES);
            }
            existing.setAccountType(requested);
        }
        if (patch.name() != null && !patch.name().isBlank()) {
            existing.setName(patch.name().trim());
        }
        if (patch.plan() != null && !patch.plan().isBlank()) {
            existing.setPlan(patch.plan().trim().toLowerCase());
        }

        existing.setUpdatedAt(Instant.now());
        Account saved = accountRepository.save(existing);
        return new AccountResponse(saved.getId(), saved.getName(), saved.getAccountType(), saved.getPlan());
    }

    /**
     * Members of an account, with the email that identifies them to a human.
     *
     * <p>The email is joined in rather than left to the caller: a members screen showing raw UUIDs
     * is unusable, and every caller would otherwise have to fan out one user lookup per row.
     */
    @GetMapping("/accounts/{accountId}/members")
    public List<MemberResponse> members(@PathVariable UUID accountId) {
        return membershipRepository.findByAccountId(accountId).stream()
                .map(m -> new MemberResponse(
                        m.getId(),
                        m.getUserId(),
                        userRepository.findById(m.getUserId()).map(User::getEmail).orElse(null),
                        m.getRole(),
                        m.getStatus()))
                .toList();
    }

    /**
     * Changes an existing member's role.
     *
     * <p>{@code @Transactional} because {@code upsertMembership} is a {@code @Modifying} native
     * query, which JPA refuses to run without one.
     */
    @PutMapping("/accounts/{accountId}/members/{userId}")
    @Transactional
    public MemberResponse updateMember(@PathVariable UUID accountId,
                                       @PathVariable UUID userId,
                                       @RequestBody MemberPatch patch) {
        if (patch.role() == null || patch.role().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role is required");
        }
        membershipRepository.findByAccountIdAndUserId(accountId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));
        membershipRepository.upsertMembership(accountId, userId, patch.role().trim().toUpperCase());
        return membershipRepository.findByAccountIdAndUserId(accountId, userId)
                .map(m -> new MemberResponse(m.getId(), m.getUserId(),
                        userRepository.findById(m.getUserId()).map(User::getEmail).orElse(null),
                        m.getRole(), m.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Membership not found"));
    }

    @DeleteMapping("/accounts/{accountId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID accountId, @PathVariable UUID userId) {
        membershipRepository.findByAccountIdAndUserId(accountId, userId)
                .ifPresent(membershipRepository::delete);
    }

    // ------------------------------------------------------------------ invitations

    /**
     * Records an offer to join an account.
     *
     * <p>The caller supplies the token hash; the token itself never reaches this service. Storing
     * only the hash means a database dump contains no usable invitations, for the same reason it
     * contains no usable passwords.
     */
    @PostMapping("/accounts/{accountId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public InvitationResponse createInvitation(@PathVariable UUID accountId,
                                               @RequestBody InvitationRequest request) {
        if (request.email() == null || request.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email is required");
        }
        if (request.tokenHash() == null || request.tokenHash().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tokenHash is required");
        }
        accountRepository.findById(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));

        String email = request.email().trim().toLowerCase();
        // Re-inviting replaces the outstanding offer rather than adding a second live token: two
        // valid tokens would mean revoking the visible one still lets the other in.
        invitationRepository.findByAccountIdAndEmailAndStatus(accountId, email, "pending")
                .ifPresent(existing -> {
                    existing.setStatus("revoked");
                    existing.setUpdatedAt(Instant.now());
                    invitationRepository.save(existing);
                });

        invitationRepository.insertInvitation(
                accountId,
                email,
                request.role() == null || request.role().isBlank() ? "MARKETER" : request.role().trim().toUpperCase(),
                request.brandId(),
                request.tokenHash(),
                request.invitedBy(),
                request.expiresAt() == null ? Instant.now().plus(Duration.ofDays(7)) : request.expiresAt());

        return invitationRepository.findByTokenHash(request.tokenHash())
                .map(TenancyController::toInvitationResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Invitation was not persisted"));
    }

    @GetMapping("/accounts/{accountId}/invitations")
    public List<InvitationResponse> invitations(@PathVariable UUID accountId) {
        return invitationRepository.findByAccountIdOrderByCreatedAtDesc(accountId).stream()
                .map(TenancyController::toInvitationResponse)
                .toList();
    }

    /** Looks an invitation up by token hash, for redemption. */
    @GetMapping("/invitations/by-token/{tokenHash}")
    public InvitationResponse invitationByToken(@PathVariable String tokenHash) {
        return invitationRepository.findByTokenHash(tokenHash)
                .map(TenancyController::toInvitationResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
    }

    /**
     * Marks an invitation accepted and creates the membership in one transaction.
     *
     * <p>Both writes together: an invitation marked accepted without a membership would leave the
     * user unable to reach the account and unable to retry, since the token is now spent.
     */
    @PostMapping("/invitations/{id}/accept")
    @Transactional
    public MemberResponse acceptInvitation(@PathVariable UUID id, @RequestBody AcceptRequest request) {
        if (request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        MemberInvitation invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (!"pending".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invitation is " + invitation.getStatus());
        }
        if (invitation.getExpiresAt() != null && invitation.getExpiresAt().isBefore(Instant.now())) {
            invitation.setStatus("expired");
            invitation.setUpdatedAt(Instant.now());
            invitationRepository.save(invitation);
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation has expired");
        }

        membershipRepository.upsertMembership(invitation.getAccountId(), request.userId(), invitation.getRole());
        grantBrandAccess(invitation, request.userId());

        invitation.setStatus("accepted");
        invitation.setAcceptedBy(request.userId());
        invitation.setAcceptedAt(Instant.now());
        invitation.setUpdatedAt(Instant.now());
        invitationRepository.save(invitation);

        return membershipRepository.findByAccountIdAndUserId(invitation.getAccountId(), request.userId())
                .map(m -> new MemberResponse(m.getId(), m.getUserId(),
                        userRepository.findById(m.getUserId()).map(User::getEmail).orElse(null),
                        m.getRole(), m.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Membership was not created"));
    }

    @PostMapping("/invitations/{id}/revoke")
    public InvitationResponse revokeInvitation(@PathVariable UUID id) {
        MemberInvitation invitation = invitationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if ("accepted".equals(invitation.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invitation was already accepted; remove the member instead");
        }
        invitation.setStatus("revoked");
        invitation.setUpdatedAt(Instant.now());
        return toInvitationResponse(invitationRepository.save(invitation));
    }

    /**
     * Gives a newly accepted member the brand rows their role needs.
     *
     * <p>OWNER, ADMIN and FINANCE reach every brand in the account implicitly, so they need no
     * rows — {@code findAccessibleBrands} treats them as account-wide. Every other role reaches
     * <em>only</em> what {@code brand_access} grants: without this, a MANAGER, MARKETER or ANALYST
     * would hold a membership yet see zero brands, which reads as a broken account rather than a
     * permissions decision.
     *
     * <p>A brand-scoped invitation grants exactly the brand it named. An unscoped one grants every
     * brand the account currently has — matching what an admin would otherwise do by hand, and
     * deliberately not retroactive: brands added later need a fresh grant, which is the safer
     * default for an agency taking on a new client.
     */
    private void grantBrandAccess(MemberInvitation invitation, UUID userId) {
        if (ACCOUNT_WIDE_ROLES.contains(invitation.getRole())) {
            return;
        }
        UUID membershipId = membershipRepository
                .findByAccountIdAndUserId(invitation.getAccountId(), userId)
                .map(Membership::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Membership was not created"));

        List<UUID> brandIds = invitation.getBrandId() != null
                ? List.of(invitation.getBrandId())
                : brandRepository.findByAccountIdOrderByNameAsc(invitation.getAccountId())
                        .stream().map(Brand::getId).toList();

        for (UUID brandId : brandIds) {
            brandAccessRepository.grant(membershipId, brandId, invitation.getRole());
        }
    }

    private static InvitationResponse toInvitationResponse(MemberInvitation invitation) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getAccountId(),
                invitation.getEmail(),
                invitation.getRole(),
                invitation.getBrandId(),
                invitation.getStatus(),
                invitation.getExpiresAt(),
                invitation.getCreatedAt());
    }

    public record BrandAccessResponse(
            UUID brandId,
            String brandName,
            UUID accountId,
            String accountType,
            String accountRole,
            String effectiveRole) {
    }

    public record ProvisionRequest(UUID userId,
                                   String workspaceName,
                                   String accountType,
                                   String role,
                                   String plan) {
    }

    public record ProvisionResponse(UUID accountId, UUID brandId, String accountType, String role) {
    }

    /** {@code plan} added for M2.3 — where a billing integration writes an upgrade. */
    public record AccountPatch(String accountType, String name, String plan) {
    }

    public record AccountResponse(UUID id, String name, String accountType, String plan) {
    }

    public record MemberPatch(String role) {
    }

    public record InvitationRequest(String email,
                                    String role,
                                    UUID brandId,
                                    String tokenHash,
                                    UUID invitedBy,
                                    Instant expiresAt) {
    }

    public record AcceptRequest(UUID userId) {
    }

    /** Deliberately has no token field — the token exists only in the invite response. */
    public record InvitationResponse(UUID id,
                                     UUID accountId,
                                     String email,
                                     String role,
                                     UUID brandId,
                                     String status,
                                     Instant expiresAt,
                                     Instant createdAt) {
    }

    public record MemberResponse(UUID membershipId, UUID userId, String email, String role, String status) {
    }
}
