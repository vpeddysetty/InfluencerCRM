package com.influencer.attribution.infrastructure;

import com.influencer.attribution.domain.MarketplaceConnection;
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
}
