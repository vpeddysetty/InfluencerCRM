package com.influencer.dao.identity.api;

import com.influencer.dao.identity.domain.CreatorIdentity;
import com.influencer.dao.identity.domain.CreatorIdentityLink;
import com.influencer.dao.identity.domain.CreatorInvite;
import com.influencer.dao.identity.infrastructure.CreatorIdentityLinkRepository;
import com.influencer.dao.identity.infrastructure.CreatorIdentityRepository;
import com.influencer.dao.identity.infrastructure.CreatorInviteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the timestamps a redeemed invitation has to stamp itself.
 *
 * <p>No creator could accept an invitation in production. The insert failed with "null value in
 * column created_at of relation creator_identities violates not-null constraint", and the BFF turns
 * any DAO failure here into one deliberately vague 409 — "This invitation is no longer valid" — so
 * the screen blamed the token and the real cause never surfaced. The token was fine.
 *
 * <p>{@code created_at} and {@code updated_at} are {@code not null default now()}, which reads as
 * though the database fills them in. It does not: a Postgres default applies only when the column is
 * OMITTED from the INSERT, and both are mapped fields Hibernate always names, so an unset field is
 * written as an explicit NULL and rejected. This is the third place that trap has bitten —
 * {@code campaign_creators}, then {@code creators}, now the identity — and the first two each have
 * their own test for it.
 *
 * <p>Redemption was the ONE identity path that did not stamp them. Every other one —
 * {@code CreatorIdentityController}, the portal session, email verification, tenancy — already
 * called {@code setCreatedAt}, which is why nothing else was broken.
 */
class CreatorInviteRedemptionTest {

    private static final UUID BRAND_ID = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID CREATOR_ID = UUID.fromString("c0000000-0000-0000-0000-00000000000c");
    private static final String TOKEN_HASH = "hash-of-a-single-use-token";

    private CreatorInviteRepository inviteRepository;
    private CreatorIdentityRepository identityRepository;
    private CreatorIdentityLinkRepository linkRepository;
    private CreatorInviteController controller;

    @BeforeEach
    void setUp() {
        inviteRepository = mock(CreatorInviteRepository.class);
        identityRepository = mock(CreatorIdentityRepository.class);
        linkRepository = mock(CreatorIdentityLinkRepository.class);
        controller = new CreatorInviteController(inviteRepository, identityRepository, linkRepository);

        CreatorInvite invite = new CreatorInvite();
        invite.setId(UUID.randomUUID());
        invite.setBrandId(BRAND_ID);
        invite.setCreatorId(CREATOR_ID);
        invite.setEmail("maya@example.com");
        invite.setStatus("pending");
        invite.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));

        when(inviteRepository.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(invite));
        when(identityRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(linkRepository.findByCreatorIdentityIdAndBrandId(any(), any())).thenReturn(Optional.empty());
        when(identityRepository.save(any(CreatorIdentity.class))).thenAnswer(i -> {
            CreatorIdentity c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(linkRepository.save(any(CreatorIdentityLink.class))).thenAnswer(i -> {
            CreatorIdentityLink l = i.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            return l;
        });
        when(inviteRepository.save(any(CreatorInvite.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("a new identity is stamped, so the insert is not rejected")
    void newIdentityCarriesItsTimestamps() {
        controller.redeem(TOKEN_HASH, new CreatorInviteController.RedeemRequest("Maya Okonjo", null));

        ArgumentCaptor<CreatorIdentity> captor = ArgumentCaptor.forClass(CreatorIdentity.class);
        verify(identityRepository).save(captor.capture());
        CreatorIdentity saved = captor.getValue();

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        // Not asserted as equal to a fixed clock: the point is only that neither reaches the
        // database as NULL, and pinning the instant would make this a test of Instant.now().
        assertThat(saved.getEmail()).isEqualTo("maya@example.com");
        assertThat(saved.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("the confirmed link is stamped too")
    void newLinkCarriesItsTimestamps() {
        controller.redeem(TOKEN_HASH, new CreatorInviteController.RedeemRequest("Maya Okonjo", null));

        ArgumentCaptor<CreatorIdentityLink> captor = ArgumentCaptor.forClass(CreatorIdentityLink.class);
        verify(linkRepository).save(captor.capture());
        CreatorIdentityLink saved = captor.getValue();

        // creator_identity_links.created_at is not-null with the same never-applied default.
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo("confirmed");
        assertThat(saved.getBrandId()).isEqualTo(BRAND_ID);
    }

    @Test
    @DisplayName("the invitation is spent, so the token cannot be redeemed twice")
    void invitationIsMarkedAccepted() {
        controller.redeem(TOKEN_HASH, new CreatorInviteController.RedeemRequest("Maya Okonjo", null));

        ArgumentCaptor<CreatorInvite> captor = ArgumentCaptor.forClass(CreatorInvite.class);
        verify(inviteRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("accepted");
        assertThat(captor.getValue().getAcceptedAt()).isNotNull();
    }
}
