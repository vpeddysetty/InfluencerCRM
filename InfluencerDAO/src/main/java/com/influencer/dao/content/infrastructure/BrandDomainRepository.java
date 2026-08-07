package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.BrandDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandDomainRepository extends JpaRepository<BrandDomain, UUID> {
    List<BrandDomain> findByBrandIdOrderByCreatedAtDesc(UUID brandId);
    List<BrandDomain> findByLandingTemplateId(UUID landingTemplateId);

    /** How a public request on a custom domain resolves to a page. */
    Optional<BrandDomain> findByDomainName(String domainName);
}
