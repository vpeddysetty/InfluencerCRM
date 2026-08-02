package com.influencer.dao.finance.application;

import com.influencer.dao.finance.domain.InfluencerCommission;
import com.influencer.dao.finance.infrastructure.InfluencerCommissionRepository;
import com.influencer.dao.shared.events.DomainEvents;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Commission lifecycle for the Finance context.
 *
 * <p>Introduced in Phase 4 so the controller stops writing straight to the repository: state
 * transitions now happen in one place that can enforce them and record what happened.
 *
 * <p>Each transition emits a domain event in the same transaction as the write. Today nothing
 * consumes them, and that is fine — the point is that the record exists from the moment the
 * behaviour does, so Phase 5 can extract Finance without first having to retrofit a history.
 */
@Service
public class CommissionService {

    /** Matches the {@code context} column in the outbox. */
    private static final String CONTEXT = "finance";
    private static final String AGGREGATE = "InfluencerCommission";

    private final InfluencerCommissionRepository repository;
    private final DomainEvents events;

    public CommissionService(InfluencerCommissionRepository repository, DomainEvents events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * Records a newly accrued commission and announces it.
     *
     * <p>{@code CommissionAccrued} is the first link in the chain the plan describes:
     * SaleAttributed → CommissionAccrued → PayoutRequested → PayoutApproved → PayoutSettled.
     */
    @Transactional
    public InfluencerCommission accrue(InfluencerCommission commission) {
        if (commission.getStatus() == null || commission.getStatus().isBlank()) {
            commission.setStatus("pending");
        }

        InfluencerCommission saved = repository.save(commission);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commissionId", asString(saved.getId()));
        payload.put("creatorId", asString(saved.getCreatorId()));
        payload.put("campaignId", asString(saved.getCampaignId()));
        payload.put("attributionId", asString(saved.getAttributionId()));
        payload.put("commissionAmount", saved.getCommissionAmount() == null ? null : saved.getCommissionAmount().toString());
        payload.put("currency", saved.getCurrency());
        payload.put("status", saved.getStatus());

        events.publish(CONTEXT, AGGREGATE, saved.getId(), saved.getBrandId(),
                "CommissionAccrued", payload);
        return saved;
    }

    /**
     * Approves a commission, making it eligible for payout.
     *
     * <p>The guard is the invariant that matters here: approving something already paid would
     * silently re-open a settled obligation, so the transition is rejected rather than ignored.
     */
    @Transactional
    public InfluencerCommission approve(UUID commissionId, UUID approvedByUserId) {
        InfluencerCommission commission = repository.findById(commissionId)
                .orElseThrow(() -> new IllegalArgumentException("Commission not found: " + commissionId));

        String previousStatus = commission.getStatus();
        if ("paid".equalsIgnoreCase(previousStatus)) {
            throw new IllegalStateException("Commission " + commissionId + " is already paid and cannot be re-approved");
        }
        if ("approved".equalsIgnoreCase(previousStatus)) {
            return commission;
        }

        commission.setStatus("approved");
        InfluencerCommission saved = repository.save(commission);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commissionId", asString(saved.getId()));
        payload.put("previousStatus", previousStatus);
        payload.put("approvedByUserId", asString(approvedByUserId));
        payload.put("commissionAmount", saved.getCommissionAmount() == null ? null : saved.getCommissionAmount().toString());

        events.publish(CONTEXT, AGGREGATE, saved.getId(), saved.getBrandId(),
                "CommissionApproved", payload);
        return saved;
    }

    private String asString(UUID value) {
        return value == null ? null : value.toString();
    }
}
