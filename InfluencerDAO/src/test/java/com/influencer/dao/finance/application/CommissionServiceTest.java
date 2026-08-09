package com.influencer.dao.finance.application;

import com.influencer.dao.finance.domain.InfluencerCommission;
import com.influencer.dao.finance.infrastructure.InfluencerCommissionRepository;
import com.influencer.dao.shared.events.DomainEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the invariants and events the Finance aggregate gained in Phase 4.
 *
 * <p>Before this, the controller wrote whatever status the caller sent straight to the repository,
 * so an already-paid commission could be silently re-opened and nothing recorded that it happened.
 */
class CommissionServiceTest {

    private static final UUID BRAND_ID = UUID.fromString("b0000000-0000-0000-0000-00000000000b");
    private static final UUID COMMISSION_ID = UUID.fromString("c0000000-0000-0000-0000-00000000000c");
    private static final UUID APPROVER_ID = UUID.fromString("a0000000-0000-0000-0000-00000000000a");

    private InfluencerCommissionRepository repository;
    private DomainEvents events;
    private CommissionService service;

    @BeforeEach
    void setUp() {
        repository = mock(InfluencerCommissionRepository.class);
        events = mock(DomainEvents.class);
        service = new CommissionService(repository, events);
        when(repository.save(any(InfluencerCommission.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private InfluencerCommission commission(String status) {
        InfluencerCommission commission = new InfluencerCommission();
        commission.setId(COMMISSION_ID);
        commission.setBrandId(BRAND_ID);
        commission.setStatus(status);
        commission.setCommissionAmount(new BigDecimal("10.00"));
        commission.setCurrency("USD");
        return commission;
    }

    @Test
    @DisplayName("accruing defaults to pending and emits CommissionAccrued")
    void accrueEmitsEvent() {
        InfluencerCommission accrued = service.accrue(commission(null));

        assertThat(accrued.getStatus()).isEqualTo("pending");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq("finance"), eq("InfluencerCommission"),
                eq(COMMISSION_ID), eq(BRAND_ID), eq("CommissionAccrued"), payload.capture());

        // Tenancy must travel with the event so a consumer never has to infer which brand it is for.
        assertThat(payload.getValue()).containsEntry("commissionAmount", "10.00");
        assertThat(payload.getValue()).containsEntry("status", "pending");
    }

    @Test
    @DisplayName("approving moves pending -> approved and emits CommissionApproved")
    void approveEmitsEvent() {
        when(repository.findById(COMMISSION_ID)).thenReturn(Optional.of(commission("pending")));

        InfluencerCommission approved = service.approve(COMMISSION_ID, APPROVER_ID);

        assertThat(approved.getStatus()).isEqualTo("approved");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq("finance"), eq("InfluencerCommission"),
                eq(COMMISSION_ID), eq(BRAND_ID), eq("CommissionApproved"), payload.capture());
        assertThat(payload.getValue()).containsEntry("previousStatus", "pending");
        assertThat(payload.getValue()).containsEntry("approvedByUserId", APPROVER_ID.toString());
    }

    @Test
    @DisplayName("a paid commission cannot be re-approved, and no event is emitted")
    void paidCommissionCannotBeReapproved() {
        when(repository.findById(COMMISSION_ID)).thenReturn(Optional.of(commission("paid")));

        assertThatThrownBy(() -> service.approve(COMMISSION_ID, APPROVER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already paid");

        // The invariant must hold in the store as well as the response: nothing written, nothing
        // announced. An event for a transition that did not happen is worse than no event at all.
        verify(repository, never()).save(any(InfluencerCommission.class));
        verify(events, never()).publish(anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("re-approving an approved commission is a no-op rather than a duplicate event")
    void approvingTwiceIsIdempotent() {
        when(repository.findById(COMMISSION_ID)).thenReturn(Optional.of(commission("approved")));

        InfluencerCommission result = service.approve(COMMISSION_ID, APPROVER_ID);

        assertThat(result.getStatus()).isEqualTo("approved");
        verify(repository, never()).save(any(InfluencerCommission.class));
        verify(events, never()).publish(anyString(), anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("approving an unknown commission is rejected")
    void unknownCommissionRejected() {
        when(repository.findById(COMMISSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(COMMISSION_ID, APPROVER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");

        verify(events, never()).publish(anyString(), anyString(), any(), any(), anyString(), any());
    }
}
