package com.influencer.dao.creator.infrastructure;

import com.influencer.dao.creator.domain.Creator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
	List<Creator> findByUserId(UUID userId);

	List<Creator> findByBrandId(UUID brandId);

	/** The review queue (C2.6): everything a rule did not resolve. */
	List<Creator> findByBrandIdAndVettingStatus(UUID brandId, String vettingStatus);

	@Query(value = "select * from creators where user_id = :userId and platform = cast(:platform as platform_type) and handle = :handle", nativeQuery = true)
	Optional<Creator> findByUserIdAndPlatformAndHandle(@Param("userId") UUID userId, @Param("platform") String platform, @Param("handle") String handle);

	@Query(value = "select * from creators where brand_id = :brandId and platform = cast(:platform as platform_type) and handle = :handle", nativeQuery = true)
	Optional<Creator> findByBrandIdAndPlatformAndHandle(@Param("brandId") UUID brandId, @Param("platform") String platform, @Param("handle") String handle);
}
