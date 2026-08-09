package com.influencer.dao.campaign.infrastructure;

import com.influencer.dao.campaign.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
	List<ImportBatch> findByUserIdOrderByCreatedAtDesc(UUID userId);

	List<ImportBatch> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
}
