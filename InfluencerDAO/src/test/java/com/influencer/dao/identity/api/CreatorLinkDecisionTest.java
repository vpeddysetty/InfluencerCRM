package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.CreatorIdentityLink;
import com.influencer.dao.identity.infrastructure.CreatorIdentityLinkRepository;
import com.influencer.dao.identity.infrastructure.CreatorIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A brand may only decide claims that were made against it (roadmap OP-18).
 *
 * <p>This was a live cross-tenant defect, and it needed two mistakes that were individually
 * reasonable. The DAO loaded the link by {@code linkId} alone, which is the house convention —
 * tenancy is the BFF's job. The BFF checked that the caller held {@code creator:write}, which is
 * the right permission. Neither checked that the link belonged to the caller's brand, and
 * {@code linkId} comes from the URL, so any authenticated user with {@code creator:write} in any
 * brand could confirm another brand's pending claim by guessing a UUID — granting that creator
 * access to the victim brand's negotiated terms.
 *
 * <p>The test lives at the DAO because that is the layer that can be checked without a servlet
 * context, and because the guard here is the one that holds even if a future caller forgets.
 */
class CreatorLinkDecisionTest {

    private static final UUID LINK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNING_BRAND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_BRAND = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DECIDER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private CreatorIdentityLinkRepository linkRepository;
    private CreatorIdentityController controller;

    @BeforeEach
    void setUp() {
        linkRepository = mock(CreatorIdentityLinkRepository.class);
        controller = new CreatorIdentityController(
                mock(CreatorIdentityRepository.class), linkRepository);

        CreatorIdentityLink link = new CreatorIdentityLink();
        link.setId(LINK_ID);
        link.setBrandId(OWNING_BRAND);
        link.setStatus("claimed");
        when(linkRepository.findById(LINK_ID)).thenReturn(Optional.of(link));
        when(linkRepository.save(any(CreatorIdentityLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("the owning brand can confirm its own pending claim")
    void owningBrandMayDecide() {
        CreatorIdentityLink saved = controller.decide(LINK_ID,
                new CreatorIdentityController.LinkDecision("confirmed", DECIDER, OWNING_BRAND));

        assertThat(saved.getStatus()).isEqualTo("confirmed");
        assertThat(saved.getConfirmedByUserId()).isEqualTo(DECIDER);
    }

    @Test
    @DisplayName("another brand cannot confirm a claim by guessing its id")
    void otherBrandIsRefused() {
        assertThatThrownBy(() -> controller.decide(LINK_ID,
                new CreatorIdentityController.LinkDecision("confirmed", DECIDER, OTHER_BRAND)))
                .isInstanceOf(ResponseStatusException.class)
                // 404, not 403: a caller probing ids must not learn which links exist. Asserting
                // the status is part of the point — a 403 here would confirm the link is real.
                .hasMessageContaining("404");

        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("a decision that names no brand is refused rather than waved through")
    void missingBrandIsRefused() {
        // The failure mode this guards is a caller that was never updated. Treating a null brand
        // as "trusted, skip the check" would silently preserve exactly the unscoped behaviour the
        // field was added to remove, and it would do so on the path that looks like it was fixed.
        assertThatThrownBy(() -> controller.decide(LINK_ID,
                new CreatorIdentityController.LinkDecision("confirmed", DECIDER, null)))
                .isInstanceOf(ResponseStatusException.class);

        verify(linkRepository, never()).save(any());
    }

    @Test
    @DisplayName("an invalid status is refused before the brand is even considered")
    void invalidStatusStillRefused() {
        // Unchanged behaviour, pinned because the brand check was inserted near it: validation
        // order matters here only in that neither check may be skippable by failing the other.
        assertThatThrownBy(() -> controller.decide(LINK_ID,
                new CreatorIdentityController.LinkDecision("approved-ish", DECIDER, OWNING_BRAND)))
                .isInstanceOf(ResponseStatusException.class);

        verify(linkRepository, never()).save(any());
    }
}
