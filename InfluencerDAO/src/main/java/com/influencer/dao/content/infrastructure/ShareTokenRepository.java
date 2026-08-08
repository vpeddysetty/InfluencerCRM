package com.influencer.dao.content.infrastructure;

import com.influencer.dao.content.domain.ShareToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShareTokenRepository extends JpaRepository<ShareToken, UUID> {
    Optional<ShareToken> findByToken(String token);
    List<ShareToken> findByUserId(UUID userId);
    List<ShareToken> findByUserIdAndCampaignId(UUID userId, UUID campaignId);
}
