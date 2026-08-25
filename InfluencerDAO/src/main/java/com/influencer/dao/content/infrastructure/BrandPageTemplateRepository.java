package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.BrandPageTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BrandPageTemplateRepository extends JpaRepository<BrandPageTemplate, UUID> {

    /** A brand's saved templates, newest first — the only list this table has. */
    List<BrandPageTemplate> findByBrandIdOrderByCreatedAtDesc(UUID brandId);

    /**
     * Fetch scoped by brand, not by id alone.
     *
     * <p>The id arrives from the caller, so a lookup by id alone would let one brand delete
     * another's template by guessing a UUID. Scoping the query is what makes the 404 in the
     * controller a real boundary rather than a message.
     */
    Optional<BrandPageTemplate> findByIdAndBrandId(UUID id, UUID brandId);

    /** Case-insensitive, matching uq_brand_page_templates_name — see the migration. */
    Optional<BrandPageTemplate> findByBrandIdAndNameIgnoreCase(UUID brandId, String name);

    long countByBrandId(UUID brandId);
}
