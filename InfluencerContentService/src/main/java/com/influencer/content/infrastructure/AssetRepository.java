package com.influencer.content.infrastructure;

import com.influencer.content.domain.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    /** The asset picker's only query: this brand's assets, newest first. */
    List<Asset> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
}
