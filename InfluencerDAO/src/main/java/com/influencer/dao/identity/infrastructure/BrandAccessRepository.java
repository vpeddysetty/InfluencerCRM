package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Grants a membership access to a specific brand.
 *
 * <p>Only roles that are not account-wide need rows here. {@code BrandRepository.findAccessibleBrands}
 * treats OWNER, ADMIN and FINANCE as reaching every brand implicitly; every other role reaches only
 * what this table grants, so a MANAGER, MARKETER or ANALYST with no row would hold a membership and
 * still see zero brands.
 *
 * <p>Extends {@code JpaRepository<Membership, UUID>} purely to obtain an entity manager — the table
 * has no entity of its own because nothing reads it as an object, only through the access query.
 */
@Repository
public interface BrandAccessRepository extends JpaRepository<Membership, UUID> {

    /**
     * Idempotent grant, with the role cast to the {@code account_role} enum.
     *
     * <p>{@code on conflict do update} rather than {@code do nothing}: re-inviting someone at a
     * different role must move their existing access, not silently leave the old one in place.
     */
    @Modifying
    @Query(value = """
            insert into brand_access (membership_id, brand_id, role)
            values (:membershipId, :brandId, cast(:role as account_role))
            on conflict (membership_id, brand_id)
            do update set role = cast(:role as account_role)
            """, nativeQuery = true)
    void grant(@Param("membershipId") UUID membershipId,
               @Param("brandId") UUID brandId,
               @Param("role") String role);
}
