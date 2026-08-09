package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.PlatformMetrics;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Creator health monitoring after approval (roadmap C3).
 *
 * <p>Vetting is a gate; this is the relationship afterwards. A creator approved at 50k
 * followers who quietly declines to 5k is a live problem, and before this nothing would notice.
 *
 * <p><b>Alerts inform a decision; they never take one</b> (roadmap #13). Nothing in this class
 * changes a creator's vetting status, access or campaign assignment. The asymmetry is the same
 * one behind auto-approval and stronger here: a creator mid-campaign has delivered work, may be
 * owed money, and may have declined other offers to take this one. Metrics also dip for
 * legitimate reasons — an algorithm change, a break, a seasonal niche, or one viral post
 * inflating the previous baseline.
 */
@Service
public class CreatorHealthService {
    private static final Logger log = LoggerFactory.getLogger(CreatorHealthService.class);

    private static final Set<String> RESOLUTIONS = Set.of("acknowledged", "snoozed", "acted");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final SocialProfileGateway profiles;
    /** Phase H: a spike means a threshold is wrong, not that every creator declined at once. */
    private final PlatformMetrics metrics;

    public CreatorHealthService(DaoGatewayClient dao, ResponseShapeService shape,
                                SocialProfileGateway profiles, PlatformMetrics metrics) {
        this.dao = dao;
        this.shape = shape;
        this.profiles = profiles;
        this.metrics = metrics;
    }

    // ---- thresholds (C3.3) ----------------------------------------------

    /** This brand's thresholds, or the roadmap defaults when it has not set any. */
    public JsonNode thresholds(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode found = dao.get("/health-thresholds", q);
        if (found != null && found.isArray() && found.size() > 0) {
            return found.get(0);
        }
        // Defaults rather than an error: a brand should get useful alerts before it has thought
        // about thresholds, and these are the roadmap's stated values.
        ObjectNode defaults = shape.objectMapper().createObjectNode();
        defaults.put("brandId", brandId.toString());
        defaults.put("followerDropPct", 20.0);
        defaults.put("engagementDropPct", 30.0);
        defaults.put("inactiveDays", 45);
        defaults.put("windowDays", 30);
        defaults.put("alertOnNewRiskFlag", true);
        defaults.put("isDefault", true);
        return defaults;
    }

    public JsonNode saveThresholds(UUID brandId, ObjectNode payload) {
        ObjectNode body = payload.deepCopy();
        body.put("brandId", brandId.toString());
        return dao.post("/health-thresholds", body);
    }

    // ---- the refresh (C3.1, C3.2) ---------------------------------------

    /**
     * Re-read a creator's metrics, snapshot them, and raise any alerts the change warrants.
     *
     * <p>Snapshot BEFORE comparing: the history is worth keeping whether or not it trips a
     * threshold, and a comparison that fails should not cost the reading.
     *
     * @return the alerts raised by this refresh (often none)
     */
    public JsonNode refresh(UUID brandId, UUID creatorId) {
        JsonNode creator = requireCreator(brandId, creatorId);

        SocialProfileGateway.Profile profile =
                profiles.fetch(creator.path("platform").asText("instagram"), creator.path("handle").asText(""));
        if (profile == null) {
            // A creator who cannot be re-read is not a creator in decline. Alerting here would
            // punish a private account or an expired token, and the brand can see the stale
            // metricsFetchedAt for themselves.
            ObjectNode out = shape.objectMapper().createObjectNode();
            out.put("refreshed", false);
            out.put("reason", "The handle could not be resolved; metrics were left unchanged.");
            out.set("alerts", shape.objectMapper().createArrayNode());
            return out;
        }

        JsonNode previous = latestSnapshot(creatorId);
        writeSnapshot(brandId, creatorId, profile);
        JsonNode updated = writeCurrentMetrics(creator, profile);

        ArrayNode alerts = shape.objectMapper().createArrayNode();
        JsonNode thresholds = thresholds(brandId);
        for (ObjectNode alert : detect(brandId, creatorId, previous, profile, creator, thresholds)) {
            JsonNode raised = raise(alert);
            if (raised != null) {
                metrics.healthAlertRaised(alert.path("alertType").asText("unknown"));
                alerts.add(raised);
            }
        }

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("refreshed", true);
        out.set("creator", shape.creator(updated));
        out.set("alerts", alerts);
        return out;
    }

    /** The trend view (C3.6): the series, not just the current number. */
    public JsonNode history(UUID brandId, UUID creatorId) {
        requireCreator(brandId, creatorId);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("creatorId", creatorId.toString());
        JsonNode snapshots = dao.get("/creator-metric-snapshots", q);
        return snapshots == null ? shape.objectMapper().createArrayNode() : snapshots;
    }

    // ---- alerts (C3.4, C3.5) --------------------------------------------

    public JsonNode alerts(UUID brandId, String status) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        if (status != null && !status.isBlank()) {
            q.put("status", status);
        }
        JsonNode alerts = dao.get("/creator-health-alerts", q);
        return alerts == null ? shape.objectMapper().createArrayNode() : alerts;
    }

    /**
     * Acknowledge, snooze or act on an alert (C3.5).
     *
     * <p>Note what is absent: there is no "revoke" or "unapprove". Acting on an alert means
     * recording that a human decided something, not the platform doing it for them.
     */
    public JsonNode resolve(UUID brandId, UUID alertId, UUID userId, String status,
                            String note, Instant snoozedUntil) {
        String target = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An alert can be acknowledged, snoozed or acted on. It cannot revoke a creator — "
                            + "that is a decision for a person, not a threshold.");
        }

        JsonNode alert = dao.get("/creator-health-alerts/" + alertId, null);
        if (alert == null || !alert.hasNonNull("brandId")
                || !alert.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Alert not found");
        }

        ObjectNode body = alert.deepCopy();
        body.put("status", target);
        body.put("resolutionNote", note);
        if (userId != null) {
            body.put("resolvedByUserId", userId.toString());
        }
        body.put("resolvedAt", Instant.now().toString());
        if ("snoozed".equals(target)) {
            // Default a week out. A snooze with no end is a dismissal wearing a different name.
            body.put("snoozedUntil",
                    (snoozedUntil == null ? Instant.now().plus(Duration.ofDays(7)) : snoozedUntil).toString());
        }
        return dao.put("/creator-health-alerts/" + alertId, body);
    }

    // ---- detection -------------------------------------------------------

    /**
     * Compare the new reading against the previous snapshot and decide what warrants an alert.
     *
     * <p>With no previous snapshot nothing is raised: the first reading is a baseline, not a
     * decline. Alerting on it would flood a brand the day they start monitoring.
     */
    private List<ObjectNode> detect(UUID brandId, UUID creatorId, JsonNode previous,
                                    SocialProfileGateway.Profile current, JsonNode creator,
                                    JsonNode thresholds) {
        List<ObjectNode> alerts = new ArrayList<>();

        // Inactivity does not need a baseline — it is absolute, not a comparison.
        int inactiveDays = thresholds.path("inactiveDays").asInt(45);
        if (current.lastActiveAt() != null) {
            try {
                long days = Duration.between(Instant.parse(current.lastActiveAt()), Instant.now()).toDays();
                if (days > inactiveDays) {
                    alerts.add(alert(brandId, creatorId, "inactive",
                            "No activity for " + days + " days (threshold " + inactiveDays + ")",
                            null, BigDecimal.valueOf(days), null));
                }
            } catch (Exception ignored) {
                // An unparseable timestamp is not evidence of inactivity.
            }
        }

        if (previous == null) {
            return alerts;
        }

        BigDecimal prevFollowers = decimal(previous.get("followerCount"));
        BigDecimal nowFollowers = current.followerCount() == null
                ? null : BigDecimal.valueOf(current.followerCount());
        BigDecimal followerDrop = dropPercent(prevFollowers, nowFollowers);
        BigDecimal followerThreshold = decimal(thresholds.get("followerDropPct"));
        if (followerDrop != null && followerThreshold != null
                && followerDrop.compareTo(followerThreshold) > 0) {
            alerts.add(alert(brandId, creatorId, "follower_drop",
                    "Followers down " + followerDrop.setScale(1, RoundingMode.HALF_UP) + "% ("
                            + prevFollowers.toPlainString() + " to " + nowFollowers.toPlainString() + ")",
                    prevFollowers, nowFollowers, followerDrop));
        }

        BigDecimal prevEngagement = decimal(previous.get("engagementRate"));
        BigDecimal nowEngagement = current.engagementRate();
        BigDecimal engagementDrop = dropPercent(prevEngagement, nowEngagement);
        BigDecimal engagementThreshold = decimal(thresholds.get("engagementDropPct"));
        if (engagementDrop != null && engagementThreshold != null
                && engagementDrop.compareTo(engagementThreshold) > 0) {
            alerts.add(alert(brandId, creatorId, "engagement_drop",
                    "Engagement down " + engagementDrop.setScale(1, RoundingMode.HALF_UP) + "% ("
                            + prevEngagement.toPlainString() + "% to " + nowEngagement.toPlainString() + "%)",
                    prevEngagement, nowEngagement, engagementDrop));
        }

        return alerts;
    }

    /**
     * Percentage decline from previous to current, or null when it is not a decline.
     *
     * <p>Growth returns null rather than a negative number: this phase watches for decline, and
     * a "-40% drop" in an alert would read as a fall to anyone scanning a digest.
     */
    private BigDecimal dropPercent(BigDecimal previous, BigDecimal current) {
        if (previous == null || current == null || previous.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (current.compareTo(previous) >= 0) {
            return null;
        }
        return previous.subtract(current)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
    }

    private ObjectNode alert(UUID brandId, UUID creatorId, String type, String summary,
                             BigDecimal previous, BigDecimal current, BigDecimal changePct) {
        ObjectNode alert = shape.objectMapper().createObjectNode();
        alert.put("brandId", brandId.toString());
        alert.put("creatorId", creatorId.toString());
        alert.put("alertType", type);
        alert.put("summary", summary);
        if (previous != null) alert.put("previousValue", previous);
        if (current != null) alert.put("currentValue", current);
        if (changePct != null) alert.put("changePct", changePct);
        alert.put("status", "open");
        return alert;
    }

    private JsonNode raise(ObjectNode alert) {
        try {
            // The DAO returns any existing OPEN alert of this type rather than inserting a
            // duplicate, so a weekly refresh does not re-raise the same warning.
            return dao.post("/creator-health-alerts", alert);
        } catch (RuntimeException e) {
            log.warn("Could not raise health alert for creator {}: {}",
                    alert.path("creatorId").asText(), e.toString());
            return null;
        }
    }

    // ---- persistence helpers --------------------------------------------

    private JsonNode latestSnapshot(UUID creatorId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("creatorId", creatorId.toString());
        JsonNode snapshots = dao.get("/creator-metric-snapshots", q);
        if (snapshots == null || !snapshots.isArray() || snapshots.size() == 0) {
            return null;
        }
        return snapshots.get(0);   // repository orders newest first
    }

    private void writeSnapshot(UUID brandId, UUID creatorId, SocialProfileGateway.Profile profile) {
        try {
            ObjectNode snapshot = shape.objectMapper().createObjectNode();
            snapshot.put("brandId", brandId.toString());
            snapshot.put("creatorId", creatorId.toString());
            if (profile.followerCount() != null) snapshot.put("followerCount", profile.followerCount());
            if (profile.engagementRate() != null) snapshot.put("engagementRate", profile.engagementRate());
            if (profile.averageViews() != null) snapshot.put("averageViews", profile.averageViews());
            if (profile.lastActiveAt() != null) snapshot.put("lastActiveAt", profile.lastActiveAt());
            snapshot.put("metricsSource", profile.source());
            dao.post("/creator-metric-snapshots", snapshot);
        } catch (RuntimeException e) {
            // Losing one point of history is worth less than failing the refresh, but it is
            // logged: a gap in the series is exactly what makes a later trend argument weak.
            log.warn("Metric snapshot NOT written for creator {}: {}", creatorId, e.toString());
        }
    }

    /** Update the current values on the creator row — the fast read alongside the series. */
    private JsonNode writeCurrentMetrics(JsonNode creator, SocialProfileGateway.Profile profile) {
        ObjectNode body = creator.deepCopy();
        if (profile.followerCount() != null) body.put("followerCount", profile.followerCount());
        if (profile.engagementRate() != null) body.put("engagementRate", profile.engagementRate());
        if (profile.averageViews() != null) body.put("averageViews", profile.averageViews());
        if (profile.lastActiveAt() != null) body.put("lastActiveAt", profile.lastActiveAt());
        body.put("metricsSource", profile.source());
        body.put("metricsFetchedAt", Instant.now().toString());
        // jsonb-as-String on the entity; sending an object here fails deserialization.
        JsonNode demographics = body.get("audienceDemographics");
        if (demographics != null && demographics.isObject()) {
            try {
                body.put("audienceDemographics", shape.objectMapper().writeValueAsString(demographics));
            } catch (Exception ignored) {
                body.remove("audienceDemographics");
            }
        }
        JsonNode custom = body.get("customAttributes");
        if (custom != null && custom.isObject()) {
            try {
                body.put("customAttributes", shape.objectMapper().writeValueAsString(custom));
            } catch (Exception ignored) {
                body.remove("customAttributes");
            }
        }
        return dao.put("/creators/" + creator.get("id").asText(), body);
    }

    private BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode requireCreator(UUID brandId, UUID creatorId) {
        JsonNode creator = dao.get("/creators/" + creatorId, null);
        if (creator == null || !creator.hasNonNull("brandId")
                || !creator.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found");
        }
        return creator;
    }
}
