package com.influencer.dao.repository;

import com.influencer.dao.model.Creator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
	Optional<Creator> findByUserIdAndPlatformAndHandle(UUID userId, String platform, String handle);
}
