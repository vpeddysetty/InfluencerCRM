package com.influencer.identity.application;

import com.influencer.identity.domain.DeletionRequest;
import com.influencer.identity.domain.FederatedIdentity;
import com.influencer.identity.domain.Membership;
import com.influencer.identity.domain.User;
import com.influencer.identity.infrastructure.DeletionRequestRepository;
import com.influencer.identity.infrastructure.FederatedIdentityRepository;
import com.influencer.identity.infrastructure.MembershipRepository;
import com.influencer.identity.infrastructure.RefreshTokenRepository;
import com.influencer.identity.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The guard rails, not the happy path.
 *
 * <p>Each test here corresponds to a promise on /data-deletion/ or a schema decision that a naive
 * "delete the row" implementation would violate.
 */
class AccountPurgeServiceTest {

    private UserRepository users;
    private MembershipRepository memberships;
    private FederatedIdentityRepository federatedIdentities;
    private RefreshTokenRepository refreshTokens;
    private DeletionRequestRepository deletionRequests;
    private AccountPurgeService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        memberships = mock(MembershipRepository.class);
        federatedIdentities = mock(FederatedIdentityRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        deletionRequests = mock(DeletionRequestRepository.class);
        service = new AccountPurgeService(users, memberships, federatedIdentities,
                refreshTokens, deletionRequests);
        when(deletionRequests.save(any(DeletionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private DeletionRequest request(String scope, String provider) {
        DeletionRequest r = new DeletionRequest();
        r.setId(requestId);
        r.setSubjectEmail("someone@example.com");
        r.setSubjectUserId(userId);
        r.setScope(scope);
        r.setProvider(provider);
        when(deletionRequests.findById(requestId)).thenReturn(Optional.of(r));
        return r;
    }

    private User user(String passwordHash) {
        User u = new User();
        u.setId(userId);
        u.setEmail("someone@example.com");
        u.setPasswordHash(passwordHash);
        return u;
    }

    private Membership membership(String role) {
        Membership m = new Membership();
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private FederatedIdentity link(String provider) {
        FederatedIdentity f = new FederatedIdentity();
        f.setUserId(userId);
        f.setProvider(provider);
        return f;
    }

    @Test
    @DisplayName("a workspace owner is refused without explicit confirmation")
    void ownerRefusedWithoutForce() {
        request(DeletionRequest.SCOPE_ACCOUNT, null);
        when(users.findById(userId)).thenReturn(Optional.of(user("hash")));
        when(memberships.findByUserId(userId)).thenReturn(List.of(membership("owner")));

        DeletionRequest result = service.purgeAccount(requestId, false);

        assertNotNull(result.getRefusedAt(), "owning a workspace must not be silently cascaded");
        assertNull(result.getCompletedAt());
        verify(users, never()).deleteById(any());
        verify(refreshTokens, never()).deleteAllForUser(any());
    }

    @Test
    @DisplayName("a workspace owner is deleted when the cascade is confirmed")
    void ownerDeletedWithForce() {
        request(DeletionRequest.SCOPE_ACCOUNT, null);
        when(users.findById(userId)).thenReturn(Optional.of(user("hash")));
        when(memberships.findByUserId(userId)).thenReturn(List.of(membership("owner")));
        when(federatedIdentities.findByUserId(userId)).thenReturn(List.of());

        DeletionRequest result = service.purgeAccount(requestId, true);

        assertNotNull(result.getCompletedAt());
        assertNull(result.getRefusedAt());
        verify(users).deleteById(userId);
    }

    @Test
    @DisplayName("sessions are revoked before the user row goes")
    void revokesSessionsBeforeDeletingUser() {
        request(DeletionRequest.SCOPE_ACCOUNT, null);
        when(users.findById(userId)).thenReturn(Optional.of(user("hash")));
        when(memberships.findByUserId(userId)).thenReturn(List.of(membership("member")));
        when(federatedIdentities.findByUserId(userId)).thenReturn(List.of());

        service.purgeAccount(requestId, false);

        InOrder order = inOrder(refreshTokens, users);
        order.verify(refreshTokens).deleteAllForUser(userId);
        order.verify(users).deleteById(userId);
    }

    @Test
    @DisplayName("an unattributable request is refused, not silently completed")
    void unknownSubjectRefused() {
        DeletionRequest r = request(DeletionRequest.SCOPE_ACCOUNT, null);
        r.setSubjectUserId(null);

        DeletionRequest result = service.purgeAccount(requestId, false);

        assertNotNull(result.getRefusedAt());
        verify(users, never()).deleteById(any());
    }

    @Test
    @DisplayName("an already-absent user completes rather than refusing")
    void absentUserCompletes() {
        request(DeletionRequest.SCOPE_ACCOUNT, null);
        when(users.findById(userId)).thenReturn(Optional.empty());

        DeletionRequest result = service.purgeAccount(requestId, false);

        assertNotNull(result.getCompletedAt(), "the requested end state already holds");
        assertNull(result.getRefusedAt());
    }

    @Test
    @DisplayName("unlinking the last credential is refused so the account stays reachable")
    void lastCredentialRefused() {
        request(DeletionRequest.SCOPE_PROVIDER, "google");
        when(federatedIdentities.findByUserId(userId)).thenReturn(List.of(link("google")));
        when(federatedIdentities.countByUserId(userId)).thenReturn(1L);
        when(users.findById(userId)).thenReturn(Optional.of(user(null)));

        DeletionRequest result = service.purgeProviderData(requestId);

        assertNotNull(result.getRefusedAt());
        verify(federatedIdentities, never()).deleteAll(any());
    }

    @Test
    @DisplayName("a provider link is removed when a password remains")
    void providerRemovedWhenPasswordRemains() {
        request(DeletionRequest.SCOPE_PROVIDER, "google");
        when(federatedIdentities.findByUserId(userId)).thenReturn(List.of(link("google")));
        when(federatedIdentities.countByUserId(userId)).thenReturn(1L);
        when(users.findById(userId)).thenReturn(Optional.of(user("hash")));

        DeletionRequest result = service.purgeProviderData(requestId);

        assertNotNull(result.getCompletedAt());
        verify(federatedIdentities).deleteAll(anyList());
        verify(refreshTokens).deleteAllForUser(userId);
        verify(users, never()).deleteById(any());
    }

    @Test
    @DisplayName("provider deletion never touches the user row")
    void providerScopeLeavesAccountIntact() {
        request(DeletionRequest.SCOPE_PROVIDER, "facebook");
        when(federatedIdentities.findByUserId(userId))
                .thenReturn(List.of(link("facebook"), link("google")));
        when(federatedIdentities.countByUserId(userId)).thenReturn(2L);
        when(users.findById(userId)).thenReturn(Optional.of(user(null)));

        DeletionRequest result = service.purgeProviderData(requestId);

        assertNotNull(result.getCompletedAt());
        verify(users, never()).deleteById(any());
    }

    @Test
    @DisplayName("a missing connection completes without error")
    void missingConnectionCompletes() {
        request(DeletionRequest.SCOPE_PROVIDER, "facebook");
        when(federatedIdentities.findByUserId(userId)).thenReturn(List.of(link("google")));

        DeletionRequest result = service.purgeProviderData(requestId);

        assertNotNull(result.getCompletedAt());
        verify(federatedIdentities, never()).deleteAll(any());
    }

    @Test
    @DisplayName("scope and executor must agree")
    void scopeMismatchRejected() {
        request(DeletionRequest.SCOPE_PROVIDER, "google");
        assertThrows(IllegalStateException.class, () -> service.purgeAccount(requestId, false));
    }
}
