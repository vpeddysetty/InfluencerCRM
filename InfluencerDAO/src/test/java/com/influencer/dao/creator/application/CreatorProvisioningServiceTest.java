package com.influencer.dao.creator.application;

import com.influencer.dao.creator.domain.CampaignCreator;
import com.influencer.dao.creator.domain.Creator;
import com.influencer.dao.creator.infrastructure.CampaignCreatorRepository;
import com.influencer.dao.creator.infrastructure.CreatorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the not-null defaults a campaign_creator row needs before it can be persisted.
 *
 * <p>The schema declares these columns {@code not null default ...}, which reads as though the
 * database will fill them in. It will not: a Postgres default only applies when the column is
 * omitted from the INSERT, and every one of these is a mapped field that Hibernate always names.
 * An unset field is therefore written as an explicit NULL and rejected.
 *
 * <p>This is not hypothetical. A spreadsheet import that mapped any campaign_creator column failed
 * its entire batch on the first row with "The request violates a data constraint." — a message that
 * names neither the column nor the table, which is what made it expensive to find.
 */
class CreatorProvisioningServiceTest {

    private static final UUID BRAND_ID = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID BATCH_ID = UUID.fromString("d0000000-0000-0000-0000-00000000000d");
    private static final UUID CAMPAIGN_ID = UUID.fromString("c0000000-0000-0000-0000-00000000000c");
    private static final UUID CREATOR_ID = UUID.fromString("e0000000-0000-0000-0000-00000000000e");

    private CreatorRepository creatorRepository;
    private CampaignCreatorRepository campaignCreatorRepository;
    private CreatorProvisioningService service;

    @BeforeEach
    void setUp() {
        creatorRepository = mock(CreatorRepository.class);
        campaignCreatorRepository = mock(CampaignCreatorRepository.class);
        service = new CreatorProvisioningService(creatorRepository, campaignCreatorRepository);

        when(creatorRepository.existsById(CREATOR_ID)).thenReturn(true);
        when(campaignCreatorRepository.findByCampaignIdAndCreatorId(CAMPAIGN_ID, CREATOR_ID))
                .thenReturn(Optional.empty());
        when(campaignCreatorRepository.save(any(CampaignCreator.class)))
                .thenAnswer(invocation -> {
                    CampaignCreator saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId(UUID.randomUUID());
                    }
                    return saved;
                });
    }

    @Test
    @DisplayName("a new link is given every not-null default the schema declares")
    void newLinkGetsSchemaDefaults() {
        service.linkCreatorToCampaign(BRAND_ID, BATCH_ID, CAMPAIGN_ID, CREATOR_ID, new HashMap<>());

        CampaignCreator saved = captureSaved();

        // Each value mirrors schema/influencer_crm_schema.sql. A mismatch here means a row imported
        // through this path differs from one the database defaulted, which is worse than either.
        assertThat(saved.getOutreachStatus()).isEqualTo("new");
        assertThat(saved.getContractStatus()).isEqualTo("not_sent");
        assertThat(saved.getDeliverableStatus()).isEqualTo("pending");
        assertThat(saved.getPaymentStatus()).isEqualTo("pending");
        assertThat(saved.getContentReviewStatus()).isEqualTo("not_requested");
        assertThat(saved.getFeeCurrency()).isEqualTo("USD");
        assertThat(saved.getTags()).isNotNull();
        assertThat(saved.getCustomAttributes()).isEqualTo("{}");
        assertThat(saved.getPerformanceMetrics()).isEqualTo("{}");
    }

    @Test
    @DisplayName("a value supplied by the import is kept rather than overwritten by the default")
    void suppliedValuesSurvive() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("feeCurrency", "GBP");
        attributes.put("outreachStatus", "contacted");

        service.linkCreatorToCampaign(BRAND_ID, BATCH_ID, CAMPAIGN_ID, CREATOR_ID, attributes);

        CampaignCreator saved = captureSaved();
        assertThat(saved.getFeeCurrency()).isEqualTo("GBP");
        assertThat(saved.getOutreachStatus()).isEqualTo("contacted");
        // Untouched by the caller, so still defaulted.
        assertThat(saved.getContentReviewStatus()).isEqualTo("not_requested");
    }

    @Test
    @DisplayName("an imported creator is given every not-null default the schema declares")
    void newCreatorGetsSchemaDefaults() {
        when(creatorRepository.findByBrandIdAndPlatformAndHandle(BRAND_ID, "instagram", "@mayawears"))
                .thenReturn(Optional.empty());
        when(creatorRepository.save(any(Creator.class))).thenAnswer(i -> {
            Creator c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("handle", "@mayawears");
        attributes.put("name", "Maya Okonjo");
        service.findOrCreateCreator(BRAND_ID, BATCH_ID, "creator-roster.csv", attributes);

        ArgumentCaptor<Creator> captor = ArgumentCaptor.forClass(Creator.class);
        verify(creatorRepository).save(captor.capture());
        Creator saved = captor.getValue();

        // The three that were missing, and that failed every real import with
        // "null value in column content_themes ... violates not-null constraint". They are
        // `not null default ...` in the schema, but a Postgres default applies only when the column
        // is OMITTED from the INSERT -- and Hibernate always names every mapped field, so an unset
        // field is written as an explicit NULL and rejected.
        assertThat(saved.getContentThemes()).isNotNull();
        assertThat(saved.getRiskFlags()).isNotNull();
        assertThat(saved.getVettingStatus()).isEqualTo("lead");

        // The ones that were already right, asserted here so a future edit cannot quietly drop one.
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getSource()).isEqualTo("creator-roster.csv");
        assertThat(saved.getCurrency()).isEqualTo("USD");
        assertThat(saved.getTags()).isNotNull();
        assertThat(saved.getLanguages()).isNotNull();
        assertThat(saved.getContentCategories()).isNotNull();
        assertThat(saved.getAudienceDemographics()).isEqualTo("{}");
        assertThat(saved.getCustomAttributes()).isEqualTo("{}");
    }

    private CampaignCreator captureSaved() {
        ArgumentCaptor<CampaignCreator> captor = ArgumentCaptor.forClass(CampaignCreator.class);
        verify(campaignCreatorRepository).save(captor.capture());
        return captor.getValue();
    }
}
