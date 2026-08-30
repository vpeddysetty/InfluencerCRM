package com.influencer.dao.campaign.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.dao.campaign.application.ImportBatchHydrationService.HydrateImportBatchRequest;
import com.influencer.dao.campaign.application.ImportBatchHydrationService.HydrateImportBatchResponse;
import com.influencer.dao.campaign.domain.ImportBatch;
import com.influencer.dao.campaign.infrastructure.CampaignRepository;
import com.influencer.dao.campaign.infrastructure.ImportBatchRepository;
import com.influencer.dao.creator.application.CreatorProvisioningPort;
import com.influencer.dao.identity.application.BrandLookupPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the mapping that names no campaign column.
 *
 * <p>A creator roster is the commonest spreadsheet a brand has, and it has no campaign in it: names,
 * handles, emails, a fee, a notes column. Mapped the obvious way — {@code creator.*} plus
 * {@code campaign_creator.agreedFee} — every row produced a plan whose {@code campaignValues} was
 * null, because {@code HydrationRowPlan} nulls each group it found nothing for.
 *
 * <p>Two things then went wrong, and both returned 500 rather than anything a user could act on:
 * {@code resolveCampaignId} dereferenced that null on its first line, and even guarded it would
 * return null into {@code existsById}, which Spring Data rejects outright. In production this
 * surfaced as a 502 from the BFF, on the one import path a new account is most likely to take.
 *
 * <p>The fix keeps the row: the creator is still created, and only the campaign LINK is skipped —
 * dropping the whole row over an unattachable relationship would discard the roster the user came
 * to import.
 */
class ImportBatchHydrationServiceTest {

    private static final UUID BRAND_ID = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID BATCH_ID = UUID.fromString("d0000000-0000-0000-0000-00000000000d");
    private static final UUID CREATOR_ID = UUID.fromString("e0000000-0000-0000-0000-00000000000e");

    private ImportBatchRepository importBatchRepository;
    private BrandLookupPort brandLookup;
    private CampaignRepository campaignRepository;
    private CreatorProvisioningPort creatorProvisioning;
    private ImportBatchHydrationService service;

    @BeforeEach
    void setUp() {
        importBatchRepository = mock(ImportBatchRepository.class);
        brandLookup = mock(BrandLookupPort.class);
        campaignRepository = mock(CampaignRepository.class);
        creatorProvisioning = mock(CreatorProvisioningPort.class);
        service = new ImportBatchHydrationService(
                importBatchRepository, brandLookup, campaignRepository, creatorProvisioning,
                new ObjectMapper());

        ImportBatch batch = new ImportBatch();
        batch.setId(BATCH_ID);
        batch.setBrandId(BRAND_ID);
        batch.setSourceFilename("creator-roster.csv");
        // creator.* + campaign_creator.* and NOTHING for campaign — the mapping under test.
        batch.setColumnMapping("""
                [
                  {"spreadsheetColumn": "Creator Name", "targetEntity": "creator", "targetAttribute": "name"},
                  {"spreadsheetColumn": "IG handle",    "targetEntity": "creator", "targetAttribute": "handle"},
                  {"spreadsheetColumn": "email addr",   "targetEntity": "creator", "targetAttribute": "email"},
                  {"spreadsheetColumn": "agreed fee",   "targetEntity": "campaign_creator", "targetAttribute": "agreedFee"}
                ]
                """);

        when(importBatchRepository.findById(BATCH_ID)).thenReturn(Optional.of(batch));
        when(brandLookup.brandExists(BRAND_ID)).thenReturn(true);
    }

    private HydrateImportBatchRequest rosterRow(boolean dryRun) {
        HydrateImportBatchRequest request = new HydrateImportBatchRequest();
        request.setDryRun(dryRun);
        request.setRows(List.of(Map.of(
                "Creator Name", "Maya Okonjo",
                "IG handle", "@mayawears",
                "email addr", "maya@example.com",
                "agreed fee", "900")));
        return request;
    }

    @Test
    @DisplayName("a roster with no campaign column imports instead of throwing")
    void hydratesRosterWithoutCampaignColumn() {
        when(creatorProvisioning.findOrCreateCreator(any(), any(), any(), any()))
                .thenReturn(new CreatorProvisioningPort.ProvisionResult(CREATOR_ID, true));

        // The assertion that matters is that this returns at all: before the fix it threw
        // NullPointerException from resolveCampaignId, which the controller rendered as a 500.
        HydrateImportBatchResponse response = service.hydrate(BATCH_ID, rosterRow(false));

        assertThat(response).isNotNull();
        assertThat(response.getCreatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the unattachable link is skipped, and existsById is never asked about a null id")
    void skipsTheLinkRatherThanTheRow() {
        when(creatorProvisioning.findOrCreateCreator(any(), any(), any(), any()))
                .thenReturn(new CreatorProvisioningPort.ProvisionResult(CREATOR_ID, true));

        service.hydrate(BATCH_ID, rosterRow(false));

        // No campaign was named and none exists to look up, so there is nothing to link the creator
        // to. Spring Data throws InvalidDataAccessApiUsageException on a null id, so the guard has
        // to sit before the call rather than inside it.
        verify(campaignRepository, never()).existsById(null);
        verify(creatorProvisioning, never())
                .linkCreatorToCampaign(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("preview counts the same row without touching anything")
    void previewIsUnaffected() {
        HydrateImportBatchResponse response = service.preview(BATCH_ID, rosterRow(true));

        assertThat(response.isDryRun()).isTrue();
        assertThat(response.getPlannedOperationCount()).isEqualTo(2);
        verify(creatorProvisioning, never()).findOrCreateCreator(any(), any(), any(), any());
    }
}
