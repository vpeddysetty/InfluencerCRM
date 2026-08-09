package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * The account's live subscription, if it has one.
     *
     * <p>Excludes {@code cancelled} to match the partial unique index, which permits many
     * cancelled rows but only one of anything else. Without the same filter here, an account that
     * cancelled and resubscribed would return two rows and the caller would get an arbitrary one.
     */
    Optional<Subscription> findFirstByAccountIdAndStatusNotOrderByCreatedAtDesc(UUID accountId, String status);

    /** Every subscription an account has ever had, newest first — the billing history. */
    List<Subscription> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    Optional<Subscription> findByProviderAndProviderRef(String provider, String providerRef);
}
