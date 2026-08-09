package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.BillingEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingEventRepository extends JpaRepository<BillingEvent, UUID> {

    /** The replay lookup: has this provider already delivered this event id? */
    Optional<BillingEvent> findByProviderAndEventId(String provider, String eventId);
}
