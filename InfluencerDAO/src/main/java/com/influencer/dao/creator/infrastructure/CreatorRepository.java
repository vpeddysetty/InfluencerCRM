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

	/**
	 * Search and filter one brand's roster (roadmap PR-67).
	 *
	 * <p><b>Why native SQL.</b> {@code platform} is a Postgres enum, and the existing handle lookups
	 * above already cast it for the same reason -- a JPQL parameter binds as text and the comparison
	 * fails at runtime rather than at compile time. Keeping this consistent with its neighbours
	 * matters more than the derived-query style it gives up.
	 *
	 * <p><b>Every filter is optional, and null means "do not filter".</b> The
	 * {@code :param is null or ...} shape lets one query serve every combination without building
	 * SQL by hand -- string concatenation here would be the injection surface, on a table holding
	 * other people's contact details.
	 *
	 * <p><b>The text search is case-insensitive across handle, name and email.</b> A marketer
	 * looking for someone types whichever of the three they remember, and a search that only
	 * matched one of them would read as "this creator is not here".
	 *
	 * <p><b>Every parameter is cast explicitly.</b> Postgres cannot infer the type of a parameter
	 * whose only use is {@code :p is null}, and reports "could not determine data type of parameter
	 * $2" at runtime -- not at startup, so it passes every check short of executing the query. The
	 * casts are load-bearing; removing them for tidiness reintroduces a 500.
	 *
	 * <p><b>Follower bounds are inclusive.</b> A filter for "50k and up" that excluded exactly
	 * 50,000 would be wrong in the one case a user is most likely to test.
	 */
	@Query(value = """
			select * from creators
			 where brand_id = :brandId
			   and (cast(:q as text) is null or (
			         lower(coalesce(handle, '')) like lower(concat('%', cast(:q as text), '%'))
			      or lower(coalesce(name, ''))   like lower(concat('%', cast(:q as text), '%'))
			      or lower(coalesce(email, ''))  like lower(concat('%', cast(:q as text), '%'))))
			   and (cast(:niche as text) is null or lower(coalesce(niche, '')) = lower(cast(:niche as text)))
			   and (cast(:platform as text) is null or platform = cast(:platform as platform_type))
			   and (cast(:vettingStatus as text) is null or vetting_status = cast(:vettingStatus as text))
			   and (cast(:minFollowers as integer) is null or coalesce(follower_count, 0) >= cast(:minFollowers as integer))
			   and (cast(:maxFollowers as integer) is null or coalesce(follower_count, 0) <= cast(:maxFollowers as integer))
			 order by created_at desc
			""", nativeQuery = true)
	List<Creator> search(@Param("brandId") UUID brandId,
	                     @Param("q") String q,
	                     @Param("niche") String niche,
	                     @Param("platform") String platform,
	                     @Param("vettingStatus") String vettingStatus,
	                     @Param("minFollowers") Integer minFollowers,
	                     @Param("maxFollowers") Integer maxFollowers);

	@Query(value = "select * from creators where user_id = :userId and platform = cast(:platform as platform_type) and handle = :handle", nativeQuery = true)
	Optional<Creator> findByUserIdAndPlatformAndHandle(@Param("userId") UUID userId, @Param("platform") String platform, @Param("handle") String handle);

	@Query(value = "select * from creators where brand_id = :brandId and platform = cast(:platform as platform_type) and handle = :handle", nativeQuery = true)
	Optional<Creator> findByBrandIdAndPlatformAndHandle(@Param("brandId") UUID brandId, @Param("platform") String platform, @Param("handle") String handle);

	/**
	 * The same creator's rows across a GIVEN set of brands (roadmap PR-66).
	 *
	 * <p><b>The brand list is a parameter, not a query.</b> This deliberately does not ask "which
	 * brands work with this handle" -- that question spans tenants, and its answer would leak one
	 * customer's roster to another. The caller passes the brands it has already been granted, and
	 * this only sorts the rows within them. A caller that passed the wrong list would be the bug;
	 * a query that computed the list here would be the vulnerability.
	 *
	 * <p>Matched on {@code (platform, handle)} because that is what identifies a person across
	 * brands -- {@code uq_creators_brand_platform_handle} makes the row itself per-brand by design,
	 * so there is no shared id to join on and nothing here merges the rows.
	 */
	@Query(value = """
			select * from creators
			 where platform = cast(:platform as platform_type)
			   and lower(handle) = lower(:handle)
			   and brand_id in (:brandIds)
			 order by created_at asc
			""", nativeQuery = true)
	List<Creator> findAcrossBrands(@Param("platform") String platform,
	                               @Param("handle") String handle,
	                               @Param("brandIds") List<UUID> brandIds);

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
