package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.LandingTemplateVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LandingTemplateVersionRepository extends JpaRepository<LandingTemplateVersion, UUID> {

    /** History for a page, newest first — the only read this table has. */
    List<LandingTemplateVersion> findByLandingTemplateIdOrderByVersionNoDesc(UUID landingTemplateId);

    Optional<LandingTemplateVersion> findByLandingTemplateIdAndVersionNo(UUID landingTemplateId, Integer versionNo);

    /**
     * Next version number for a page. Returns 1 for a page with no history.
     *
     * COALESCE(max)+1 races under concurrent saves — two savers can read the same max.
     * uq_landing_versions_template_no turns that race into a constraint violation rather
     * than two rows silently sharing a number, which is the failure we can actually see
     * and retry. At one-editor-per-page this is not hit in practice; the constraint is
     * there so that assumption failing is loud instead of silent.
     */
    @Query("select coalesce(max(v.versionNo), 0) + 1 from LandingTemplateVersion v "
            + "where v.landingTemplateId = :templateId")
    Integer nextVersionNo(@Param("templateId") UUID templateId);
}
