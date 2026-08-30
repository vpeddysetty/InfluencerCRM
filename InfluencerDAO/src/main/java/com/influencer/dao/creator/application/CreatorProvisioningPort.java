package com.influencer.dao.creator.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Creator context's published contract for provisioning creators on behalf of another context.
 *
 * <p>Spreadsheet import (Campaign context) legitimately needs to create creators and link them to
 * campaigns, but must not reach into the Creator context's entities or repositories to do it — that
 * coupling is exactly what makes a context impossible to extract later. This port is the only
 * sanctioned route across the boundary.
 *
 * <p>The signatures speak in ids, primitives and attribute maps, never in {@code Creator} or
 * {@code CampaignCreator}. The caller therefore never sees a Creator-context type, which is the
 * property ArchUnit enforces and the property that lets Phase 5 swap this in-process implementation
 * for an HTTP or event-driven adapter without touching a single caller.
 *
 * <p>Attribute maps mirror how the importer already works: column mappings produce a dynamic bag of
 * field names, so a fixed record here would just push the same dynamism one layer out.
 */
public interface CreatorProvisioningPort {

    /**
     * Finds a creator by its natural key within a brand, or creates one from {@code attributes}.
     *
     * <p>Per the per-brand creator decision (migration plan §3.4) the natural key is
     * (brand, platform, handle): the same handle under two brands is two independent creators.
     *
     * @return the resolved creator and whether this call created it
     */
    ProvisionResult findOrCreateCreator(UUID brandId,
                                        UUID importBatchId,
                                        String defaultSource,
                                        Map<String, Object> attributes);

    /** Resolves an existing creator by natural key without creating one. */
    Optional<UUID> findCreatorId(UUID brandId, String platform, String handle);

    /**
     * Resolves the creator an invited email belongs to, creating one if the brand has nobody.
     *
     * <p>A creator invitation is sent to an ADDRESS -- see V46, which breaks the bootstrap
     * circularity by letting a brand invite someone it has no record of yet. But
     * {@code creator_identity_links.creator_id} is not-null, so redemption needs a creator row to
     * point the confirmed link at, and the natural key (brand, platform, handle) cannot be built
     * from an email alone.
     *
     * <p>Existing-first, deliberately: most invited creators were imported from the brand's own
     * spreadsheet minutes earlier, and creating a second row would split their fees and coupons
     * across two records that look identical in the roster.
     *
     * <p>When it does create one, the handle is derived from the email and prefixed
     * {@code invited:} -- {@code creators.handle} is NOT NULL and an invitation carries no platform
     * identifier, so it has to be something, and it must not look like a real Instagram handle
     * somebody could message.
     *
     * @param displayName what the creator called themselves when accepting; used as the name only.
     */
    UUID findOrCreateCreatorForEmail(UUID brandId, String email, String displayName);

    boolean creatorExists(UUID creatorId);

    /** Links a creator to a campaign, or updates the existing link. */
    ProvisionResult linkCreatorToCampaign(UUID brandId,
                                          UUID importBatchId,
                                          UUID campaignId,
                                          UUID creatorId,
                                          Map<String, Object> attributes);

    boolean isLinkedToCampaign(UUID campaignId, UUID creatorId);


    /**
     * @param id      the resolved entity id
     * @param created true when this call inserted the row, false when it matched an existing one
     */
    record ProvisionResult(UUID id, boolean created) {
    }
}
