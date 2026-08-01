package com.influencer.dao.repository;

import com.influencer.dao.model.MarketplaceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MarketplaceConnectionRepository extends JpaRepository<MarketplaceConnection, UUID> {
    List<MarketplaceConnection> findByUserId(UUID userId);
    List<MarketplaceConnection> findByUserIdAndProviderKey(UUID userId, String providerKey);
}
