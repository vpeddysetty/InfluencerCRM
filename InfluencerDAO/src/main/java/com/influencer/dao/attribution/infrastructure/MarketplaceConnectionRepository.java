package com.influencer.dao.attribution.infrastructure;

import com.influencer.dao.attribution.domain.MarketplaceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MarketplaceConnectionRepository extends JpaRepository<MarketplaceConnection, UUID> {
    List<MarketplaceConnection> findByUserId(UUID userId);

    List<MarketplaceConnection> findByBrandId(UUID brandId);
    List<MarketplaceConnection> findByUserIdAndProviderKey(UUID userId, String providerKey);

    List<MarketplaceConnection> findByBrandIdAndProviderKey(UUID brandId, String providerKey);

    /**
     * Resolves the store an inbound webhook came from.
     *
     * <p>Deliberately NOT filtered by brand: the whole point is that the webhook does not know its
     * brand, and this lookup is what supplies it. The store identifier is the provider's own and is
     * the only thing the request can be trusted to carry — and it only becomes authoritative once
     * the signature is checked against this row's credentials.
     */
    List<MarketplaceConnection> findByProviderKeyAndExternalAccountRef(String providerKey,
                                                                       String externalAccountRef);
}
