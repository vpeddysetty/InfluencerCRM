package com.influencer.campaign.ports;

import java.util.Optional;
import java.util.UUID;

/**
 * The Identity context's published contract for answering "does this brand exist?" and
 * "what is it called?" without exposing the {@code Brand} entity or its repository.
 *
 * <p>Other contexts store {@code brandId} as a plain UUID with no foreign key across the boundary
 * (migration plan §5.2), so they need a sanctioned way to validate one. Reaching into
 * {@code identity.infrastructure} directly would couple every context to Identity's persistence and
 * block its extraction in Phase 5.
 */
public interface BrandLookupPort {

    boolean brandExists(UUID brandId);

    /** The brand's display name, for contexts that render it. Empty when the brand is unknown. */
    Optional<String> brandName(UUID brandId);
}
