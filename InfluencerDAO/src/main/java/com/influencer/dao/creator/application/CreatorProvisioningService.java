package com.influencer.dao.creator.application;

import com.influencer.dao.creator.domain.CampaignCreator;
import com.influencer.dao.creator.domain.Creator;
import com.influencer.dao.creator.infrastructure.CampaignCreatorRepository;
import com.influencer.dao.creator.infrastructure.CreatorRepository;
import com.influencer.dao.shared.support.AttributeBinder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Creator context's implementation of {@link CreatorProvisioningPort}.
 *
 * <p>All creator and campaign-creator persistence now lives behind this boundary. The Campaign
 * context's importer previously reached directly into {@code CreatorRepository} and mutated
 * {@code Creator} entities; it now goes through the port, so the Creator context owns its own
 * defaults and invariants and can be extracted in Phase 5 without changing the caller.
 */
@Service
public class CreatorProvisioningService implements CreatorProvisioningPort {

    private final CreatorRepository creatorRepository;
    private final CampaignCreatorRepository campaignCreatorRepository;

    public CreatorProvisioningService(CreatorRepository creatorRepository,
                                      CampaignCreatorRepository campaignCreatorRepository) {
        this.creatorRepository = creatorRepository;
        this.campaignCreatorRepository = campaignCreatorRepository;
    }

    @Override
    public ProvisionResult findOrCreateCreator(UUID brandId,
                                               UUID importBatchId,
                                               String defaultSource,
                                               Map<String, Object> attributes) {
        String handle = text(attributes.get("handle"));
        if (handle == null || handle.isBlank()) {
            throw new IllegalArgumentException("creator.handle is required");
        }

        String platform = text(attributes.get("platform"));
        if (platform == null || platform.isBlank()) {
            platform = "instagram";
            attributes.put("platform", platform);
        }

        // Natural key is (brand, platform, handle) — see migration plan §3.4: the same handle
        // under two brands is two independent creators, each with its own commercial terms.
        Optional<Creator> existing =
                creatorRepository.findByBrandIdAndPlatformAndHandle(brandId, platform, handle);
        Creator creator = existing.orElseGet(Creator::new);

        creator.setBrandId(brandId);
        creator.setImportBatchId(importBatchId);
        AttributeBinder.applyValues(creator, attributes);
        creator.setHandle(handle);
        creator.setPlatform(platform);
        applyDefaults(creator, defaultSource);

        Creator saved = creatorRepository.save(creator);
        return new ProvisionResult(saved.getId(), existing.isEmpty());
    }

    @Override
    public Optional<UUID> findCreatorId(UUID brandId, String platform, String handle) {
        if (handle == null || handle.isBlank()) {
            return Optional.empty();
        }
        String resolvedPlatform = (platform == null || platform.isBlank()) ? "instagram" : platform;
        return creatorRepository.findByBrandIdAndPlatformAndHandle(brandId, resolvedPlatform, handle)
                .map(Creator::getId);
    }

    @Override
    public boolean creatorExists(UUID creatorId) {
        return creatorId != null && creatorRepository.existsById(creatorId);
    }

    @Override
    public ProvisionResult linkCreatorToCampaign(UUID brandId,
                                                 UUID importBatchId,
                                                 UUID campaignId,
                                                 UUID creatorId,
                                                 Map<String, Object> attributes) {
        if (campaignId == null || creatorId == null) {
            throw new IllegalArgumentException("campaign_creator requires campaignId and creatorId");
        }
        if (!creatorRepository.existsById(creatorId)) {
            throw new IllegalArgumentException("creatorId " + creatorId + " does not exist");
        }

        Optional<CampaignCreator> existing =
                campaignCreatorRepository.findByCampaignIdAndCreatorId(campaignId, creatorId);
        CampaignCreator link = existing.orElseGet(CampaignCreator::new);

        link.setBrandId(brandId);
        link.setImportBatchId(importBatchId);
        link.setCampaignId(campaignId);
        link.setCreatorId(creatorId);
        AttributeBinder.applyValues(link, attributes);
        // Re-applied after binding: the attribute map must never be able to move a link to a
        // different campaign or creator than the caller resolved.
        link.setCampaignId(campaignId);
        link.setCreatorId(creatorId);

        if (link.getTags() == null) {
            link.setTags(new ArrayList<>());
        }
        if (link.getCustomAttributes() == null) {
            link.setCustomAttributes("{}");
        }

        CampaignCreator saved = campaignCreatorRepository.save(link);
        return new ProvisionResult(saved.getId(), existing.isEmpty());
    }

    @Override
    public boolean isLinkedToCampaign(UUID campaignId, UUID creatorId) {
        return campaignCreatorRepository.findByCampaignIdAndCreatorId(campaignId, creatorId).isPresent();
    }


    /** Defaults the Creator context owns, rather than letting each caller invent its own. */
    private void applyDefaults(Creator creator, String defaultSource) {
        if (creator.getSource() == null) {
            creator.setSource(defaultSource);
        }
        if (creator.getStatus() == null) {
            creator.setStatus("active");
        }
        if (creator.getTags() == null) {
            creator.setTags(new String[0]);
        }
        if (creator.getLanguages() == null) {
            creator.setLanguages(new String[0]);
        }
        if (creator.getContentCategories() == null) {
            creator.setContentCategories(new String[0]);
        }
        if (creator.getAudienceDemographics() == null) {
            creator.setAudienceDemographics("{}");
        }
        if (creator.getCurrency() == null) {
            creator.setCurrency("USD");
        }
        if (creator.getCustomAttributes() == null) {
            creator.setCustomAttributes("{}");
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
