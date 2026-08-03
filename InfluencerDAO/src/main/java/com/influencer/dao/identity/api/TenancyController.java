package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.Account;
import com.influencer.dao.identity.domain.Brand;
import com.influencer.dao.identity.infrastructure.AccountRepository;
import com.influencer.dao.identity.infrastructure.BrandRepository;
import com.influencer.dao.identity.infrastructure.MembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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

    private final BrandRepository brandRepository;
    private final AccountRepository accountRepository;
    private final MembershipRepository membershipRepository;

    public TenancyController(BrandRepository brandRepository,
                             AccountRepository accountRepository,
                             MembershipRepository membershipRepository) {
        this.brandRepository = brandRepository;
        this.accountRepository = accountRepository;
        this.membershipRepository = membershipRepository;
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
     * Sets an account's type.
     *
     * <p>Exists because provisioning still happens in the {@code provision_tenancy_for_user}
     * trigger, which can only create a {@code brand} account. An agency signup therefore creates
     * the account and then promotes it, within the same signup call. When provisioning moves into
     * the application (roadmap Stage 2) the type is chosen at creation and this becomes an
     * administrative operation rather than part of signup.
     *
     * <p>The type is validated against the same two values as the database check constraint, so a
     * bad value is a 400 here rather than a constraint violation surfacing as a 500.
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

        existing.setUpdatedAt(Instant.now());
        Account saved = accountRepository.save(existing);
        return new AccountResponse(saved.getId(), saved.getName(), saved.getAccountType(), saved.getPlan());
    }

    @GetMapping("/accounts/{accountId}/members")
    public List<MemberResponse> members(@PathVariable UUID accountId) {
        return membershipRepository.findByAccountId(accountId).stream()
                .map(m -> new MemberResponse(m.getId(), m.getUserId(), m.getRole(), m.getStatus()))
                .toList();
    }

    public record BrandAccessResponse(
            UUID brandId,
            String brandName,
            UUID accountId,
            String accountType,
            String accountRole,
            String effectiveRole) {
    }

    public record AccountPatch(String accountType, String name) {
    }

    public record AccountResponse(UUID id, String name, String accountType, String plan) {
    }

    public record MemberResponse(UUID membershipId, UUID userId, String role, String status) {
    }
}
