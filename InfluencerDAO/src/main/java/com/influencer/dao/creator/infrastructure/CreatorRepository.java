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

	/**
	 * By email within a brand, for redeeming an invitation sent to an address.
	 *
	 * <p>NOT the natural key -- that is (brand, platform, handle), and email is deliberately not
	 * unique here: the same person can hold two handles for one brand. This exists so an invitation
	 * lands on the creator the brand already imported instead of creating a second row for someone
	 * they already have. Ordered so the answer is stable when there is more than one, and the
	 * caller takes the first rather than failing.
	 */
	@Query(value = "select * from creators where brand_id = :brandId and lower(email) = lower(:email) order by created_at asc", nativeQuery = true)
	List<Creator> findByBrandIdAndEmailIgnoreCase(@Param("brandId") UUID brandId, @Param("email") String email);
}
