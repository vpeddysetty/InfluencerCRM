package com.influencer.dao.creator.infrastructure;

import com.influencer.dao.creator.domain.VettingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VettingRuleRepository extends JpaRepository<VettingRule, UUID> {
    /** Ordered, because the engine takes the first match and a brand must control precedence. */
    List<VettingRule> findByBrandIdOrderByPositionAsc(UUID brandId);
}
