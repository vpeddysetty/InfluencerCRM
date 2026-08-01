package com.influencer.dao.repository;

import com.influencer.dao.model.DailyAttributionStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface DailyAttributionStatRepository extends JpaRepository<DailyAttributionStat, UUID> {
    List<DailyAttributionStat> findByUserId(UUID userId);
    List<DailyAttributionStat> findByUserIdAndDayBetween(UUID userId, LocalDate from, LocalDate to);
    List<DailyAttributionStat> findByUserIdAndCreatorId(UUID userId, UUID creatorId);
    List<DailyAttributionStat> findByUserIdAndCampaignId(UUID userId, UUID campaignId);
}
