package com.influencer.webe.identity.application;

import com.influencer.webe.security.AccountRole;

import java.util.List;
import java.util.UUID;

/**
 * Which brands a user may reach — identity's published answer to that question.
 *
 * <p><b>Why this interface exists at all.</b> {@code DaoTenancyClient} already answers it, but it
 * lives in {@code identity.infrastructure}, and §3's first boundary rule is that a context may only
 * cross into another through its {@code application} package. `PR-64` needs the answer from
 * {@code attribution}, and ArchUnit correctly refused the direct import. The fix is a published
 * port, not a relaxed assertion: the rule exists so that a context's outbound clients stay
 * replaceable, and a caller in another context is exactly what would pin them in place.
 *
 * <p><b>There must be only one definition of who sees what.</b> §5 records the failure this
 * prevents — {@code AccountRole.impliesAllBrands()} and {@code BrandRepository.findAccessibleBrands}
 * disagreeing means a user logs in seeing one set of brands and is refused on another. Anything
 * needing this answer asks here; nothing recomputes it.
 *
 * <p><b>Deliberately not cached.</b> The implementation does not cache, and this contract inherits
 * that: a revoked membership must take effect on the next request rather than whenever a cache
 * happens to expire. A stale allow-list is an access-control defect, not a performance trade.
 */
public interface BrandAccessPort {

    /**
     * Every brand this user may reach, with the role held on each.
     *
     * <p>Returns an empty list rather than null when the user reaches nothing, and skips any row
     * whose role cannot be mapped — an unknown role must not become a permissive default.
     */
    List<BrandAccess> findAccessibleBrandsForPort(UUID userId);

    /**
     * One brand a user may reach.
     *
     * <p>Carries {@code accountId} and {@code accountType} because an agency account and a brand
     * account are different products, and a caller aggregating across brands needs to know which
     * it is looking at.
     */
    record BrandAccess(UUID brandId, String brandName, UUID accountId, String accountType,
                       AccountRole role) {
    }
}
