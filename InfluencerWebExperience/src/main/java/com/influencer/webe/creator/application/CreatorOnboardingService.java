package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.creator.infrastructure.CreatorClassificationClient;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Creator onboarding: resolve a handle to metrics, classify it, record the lead
 * (roadmap Phase C).
 *
 * <p><b>The separation this class exists to preserve.</b> Metrics come from a platform
 * adapter and are stamped {@code metrics_source}; classification comes from the model and is
 * stamped {@code classification_source}. They are written from different places and never
 * mixed, so "is this number measured or guessed?" is answerable from the row.
 *
 * <p><b>Nothing here blocks a signup.</b> A profile lookup that fails and a classifier that is
 * down both degrade to a lead with fewer fields populated (C.6). A creator who cannot sign up
 * because an API was unavailable is a lost creator; an unenriched lead is a follow-up task.
 */
@Service
public class CreatorOnboardingService {

    /** Platforms a handle may be resolved against — mirrors the creator service's enum. */
    private static final Set<String> PLATFORMS = Set.of("instagram", "tiktok", "youtube", "other");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final CreatorClassificationClient agent;
    private final SocialProfileGateway profiles;

    public CreatorOnboardingService(DaoGatewayClient dao,
                                    ResponseShapeService shape,
                                    CreatorClassificationClient agent,
                                    SocialProfileGateway profiles) {
        this.dao = dao;
        this.shape = shape;
        this.agent = agent;
        this.profiles = profiles;
    }

    /**
     * Resolve a handle to metrics + classification WITHOUT persisting anything (C.2/C.3).
     *
     * <p>A preview: a brand pastes a handle and sees what would be captured before deciding to
     * add the creator. Kept separate from {@link #captureLead} so that looking is not the same
     * act as saving.
     */
    public JsonNode resolveHandle(String platform, String handle) {
        String normalizedPlatform = normalizePlatform(platform);
        if (handle == null || handle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle is required");
        }

        SocialProfileGateway.Profile profile = profiles.fetch(normalizedPlatform, handle);
        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("handle", handle.trim().replaceFirst("^@", ""));
        out.put("platform", normalizedPlatform);
        out.put("resolved", profile != null);

        if (profile == null) {
            // Not an error. Private accounts, typos and deleted profiles all land here, and the
            // caller is expected to fall back to the manual form.
            out.put("reason", "The handle could not be resolved. Enter the details manually.");
            return out;
        }

        applyMetrics(out, profile, false);   // preview: objects stay objects
        JsonNode classification = classify(profile);
        if (classification != null) {
            out.set("classification", classification);
        }
        return out;
    }

    /**
     * Create a lead for this brand from a handle (C.5).
     *
     * <p>Tenancy is unchanged from the existing model: one {@code creator.creators} row per
     * (creator, brand). A creator signing up to five brands has five rows and no brand sees
     * another's rate, notes or score.
     */
    public JsonNode captureLead(UUID brandId, UUID createdByUserId, ObjectNode payload) {
        String handle = text(payload, "handle");
        if (handle == null || handle.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "handle is required");
        }
        String platform = normalizePlatform(text(payload, "platform"));

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        if (createdByUserId != null) {
            body.put("createdByUserId", createdByUserId.toString());
        }
        body.put("handle", handle.trim().replaceFirst("^@", ""));
        body.put("platform", platform);
        body.put("status", "lead");
        body.put("leadSource", textOr(payload, "leadSource", "landing_page"));
        if (payload.hasNonNull("landingTemplateId")) {
            body.put("leadLandingTemplateId", payload.get("landingTemplateId").asText());
        }
        // Manually supplied fields are taken as given but marked as such below.
        copyIfPresent(payload, body, "name", "email");

        SocialProfileGateway.Profile profile = profiles.fetch(platform, handle);
        if (profile != null) {
            applyMetrics(body, profile, true);   // persistence: jsonb as text
            if (body.path("name").asText("").isBlank() && profile.displayName() != null) {
                body.put("name", profile.displayName());
            }
            JsonNode classification = classify(profile);
            if (classification != null) {
                applyClassification(body, classification);
            }
        } else {
            // C.6: the lead is still created. Metrics are absent rather than zeroed — zero
            // followers is a real value and a very different claim from "we do not know".
            body.put("metricsSource", "manual");
            body.put("metricsFetchedAt", Instant.now().toString());
        }

        JsonNode saved = dao.post("/creators", body);
        return shape.creator(saved);
    }

    // ---- internals -----------------------------------------------------

    /**
     * Copy platform-reported facts and stamp where they came from.
     *
     * @param forPersistence when true, jsonb fields are written as JSON <i>strings</i> because
     *                       the DAO entity maps them that way; when false they stay as objects,
     *                       which is what a UI preview wants. Getting this backwards produces
     *                       either a 400 from the DAO or a quoted blob in the browser.
     */
    private void applyMetrics(ObjectNode target, SocialProfileGateway.Profile profile, boolean forPersistence) {
        if (profile.followerCount() != null) {
            target.put("followerCount", profile.followerCount());
        }
        if (profile.engagementRate() != null) {
            target.put("engagementRate", profile.engagementRate());
        }
        if (profile.averageViews() != null) {
            target.put("averageViews", profile.averageViews());
        }
        if (profile.lastActiveAt() != null) {
            target.put("lastActiveAt", profile.lastActiveAt());
        }
        if (profile.demographics() != null) {
            if (forPersistence) {
                // audience_demographics is jsonb in Postgres but mapped as a String on the
                // entity (@JdbcTypeCode(SqlTypes.JSON) over a String field), so the DAO expects
                // the JSON as text. Sending an object fails deserialization with a 400 naming
                // only "String from Object value" — the same trap as `blocks` on templates.
                try {
                    target.put("audienceDemographics",
                            shape.objectMapper().writeValueAsString(profile.demographics()));
                } catch (Exception ignored) {
                    // Demographics are enrichment; losing them must not fail the lead.
                }
            } else {
                target.set("audienceDemographics", profile.demographics());
            }
        }
        if (profile.verified() != null) {
            target.put("metricsPlatformVerified", profile.verified());
        }
        // The provenance stamp travels with the numbers, always. This is the line that makes
        // a mocked figure distinguishable from a measured one downstream.
        target.put("metricsSource", profile.source());
        target.put("metricsFetchedAt", Instant.now().toString());
    }

    /** Ask the agent to classify. Returns null when it is unavailable — never throws. */
    private JsonNode classify(SocialProfileGateway.Profile profile) {
        ObjectNode ask = shape.objectMapper().createObjectNode();
        ask.put("handle", profile.handle());
        ask.put("platform", profile.platform());
        ask.put("display_name", profile.displayName() == null ? "" : profile.displayName());
        ask.put("recent_captions", profile.recentCaptions() == null ? "" : profile.recentCaptions());
        if (profile.followerCount() != null) {
            ask.put("follower_count", profile.followerCount());
        }
        // Returns the classification object directly, or null when unavailable.
        return agent.classify(ask);
    }

    private void applyClassification(ObjectNode target, JsonNode classification) {
        if (classification.hasNonNull("niche")) {
            target.put("niche", classification.get("niche").asText());
        }
        if (classification.hasNonNull("content_themes") && classification.get("content_themes").isArray()) {
            target.set("contentThemes", classification.get("content_themes"));
            // content_categories predates this phase and the UI already reads it; keeping both
            // in step avoids a second migration to rename a column nothing else touches yet.
            target.set("contentCategories", classification.get("content_themes"));
        }
        if (classification.hasNonNull("risk_flags") && classification.get("risk_flags").isArray()) {
            target.set("riskFlags", classification.get("risk_flags"));
        }
        if (classification.hasNonNull("summary")) {
            target.put("safetyNotes", classification.get("summary").asText());
        }
        target.put("classificationSource", classification.path("source").asText("llm"));
        target.put("classificationAt", Instant.now().toString());
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "instagram";
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!PLATFORMS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported platform '" + platform + "'. Expected one of " + new java.util.TreeSet<>(PLATFORMS));
        }
        return normalized;
    }

    private void copyIfPresent(ObjectNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            if (from.hasNonNull(field) && !from.get(field).asText().isBlank()) {
                to.put(field, from.get(field).asText());
            }
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }
}
