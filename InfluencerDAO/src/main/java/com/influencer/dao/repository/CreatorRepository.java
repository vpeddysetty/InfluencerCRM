package com.influencer.dao.repository;

import com.influencer.dao.model.Creator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorRepository extends JpaRepository<Creator, UUID> {
	@Query(value = "select * from creators where user_id = :userId and platform = cast(:platform as platform_type) and handle = :handle", nativeQuery = true)
	Optional<Creator> findByUserIdAndPlatformAndHandle(@Param("userId") UUID userId, @Param("platform") String platform, @Param("handle") String handle);
}
