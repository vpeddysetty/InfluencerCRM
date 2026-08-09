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
