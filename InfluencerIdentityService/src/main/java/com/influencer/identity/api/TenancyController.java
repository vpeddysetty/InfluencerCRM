package com.influencer.identity.api;

import com.influencer.identity.domain.Brand;
import com.influencer.identity.infrastructure.AccountRepository;
import com.influencer.identity.infrastructure.BrandRepository;
import com.influencer.identity.infrastructure.MembershipRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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

    public record MemberResponse(UUID membershipId, UUID userId, String role, String status) {
    }
}
