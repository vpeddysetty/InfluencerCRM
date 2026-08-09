package com.influencer.identity.infrastructure;

import com.influencer.identity.domain.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandRepository extends JpaRepository<Brand, UUID> {

    List<Brand> findByAccountIdOrderByNameAsc(UUID accountId);

    Optional<Brand> findByLegacyUserId(UUID legacyUserId);

    /**
     * Every brand a user may reach, and the role they hold on each.
     *
     * <p>This single query is the authority for brand access. It encodes the rule that an
     * <em>account-wide</em> role with no explicit {@code brand_access} rows implicitly reaches every
     * brand in the account, while a brand-scoped role reaches only what it was granted.
     *
     * <p>The account-wide roles are OWNER, ADMIN and FINANCE. FINANCE belongs here because
     * commissions and payouts are settled across the whole account — a finance user pinned to one
     * brand could not do their job. Omitting it originally left FINANCE users unable to log in at
     * all, since a user with zero accessible brands has no session to issue.
     *
     * <p>Must stay in step with {@code AccountRole.impliesAllBrands()}, which encodes the same rule
     * in Java for the {@code X-Brand-Id} path.
     */
    // Casts are written as cast(x as text) rather than x::text: Hibernate parses ':' as the
    // start of a named parameter, so the shorthand form is a syntax error at the database.
    // Aliases are quoted camelCase, matching the projection getters exactly. With snake_case
    // aliases the interface projection binds nothing and every field comes back null — two rows
    // of nulls rather than an error, which is precisely the kind of silent failure that looks
    // like "this user has no brands".
    @Query(value = """
            select b.id            as "brandId",
                   b.name          as "brandName",
                   b.account_id    as "accountId",
                   a.account_type  as "accountType",
                   cast(m.role as text) as "accountRole",
                   coalesce(cast(ba.role as text), cast(m.role as text)) as "effectiveRole"
              from memberships m
              join accounts a on a.id = m.account_id
              join brands   b on b.account_id = m.account_id
              left join brand_access ba on ba.membership_id = m.id and ba.brand_id = b.id
             where m.user_id = :userId
               and m.status = 'active'
               and b.status = 'active'
               and (
                     ba.id is not null
                  or m.role in ('OWNER','ADMIN','FINANCE')
               )
             order by b.name
            """, nativeQuery = true)
    List<BrandAccessRow> findAccessibleBrands(@Param("userId") UUID userId);

    /** Projection for {@link #findAccessibleBrands(UUID)}. */
    interface BrandAccessRow {
        UUID getBrandId();

        String getBrandName();

        UUID getAccountId();

        String getAccountType();

        String getAccountRole();

        /** The per-brand role when one is granted, otherwise the account-level role. */
        String getEffectiveRole();
    }
}
