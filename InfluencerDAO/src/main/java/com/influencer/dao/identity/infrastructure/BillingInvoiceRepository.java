package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.BillingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingInvoiceRepository extends JpaRepository<BillingInvoice, UUID> {

    List<BillingInvoice> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * Lookup by the provider's own id — the webhook replay guard.
     *
     * <p>A provider delivers at-least-once, so the same {@code invoice.paid} event will arrive
     * twice. Finding the existing row by its provider ref is what makes the second delivery an
     * update instead of a duplicate invoice.
     */
    Optional<BillingInvoice> findByProviderAndProviderRef(String provider, String providerRef);
}
