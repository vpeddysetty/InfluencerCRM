package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brand-creator co-editing of landing pages (roadmap Phase G, §6.1).
 *
 * <p><b>Access is a narrowing of a relationship the brand already approved.</b> A creator may
 * be invited to co-edit a page only if they hold a <i>confirmed</i>
 * {@code creator_identity_links} row against that page's brand. That link was approved by the
 * brand, so page access grants nothing new in kind — and revoking the link revokes page access
 * with it, leaving one place to cut off a creator rather than two.
 *
 * <p><b>Publishing is never a collaborator right.</b> Rights are comment or edit. A
 * collaborator may shape a page; moving it to Published requires {@code content:publish}, which
 * only account members hold. A creator cannot release a page to a brand's domain or social
 * accounts.
 *
 * <p><b>Creators never own pages.</b> There is no create-page path here. Every landing page
 * belongs to a brand (decision #1), and a creator with no brand relationship has nothing to
 * build — consistent with the rest of the platform, where a creator is someone brands work
 * with rather than an independent tenant.
 */
@Service
public class PageCollaborationService {

    private static final Set<String> RIGHTS = Set.of("comment", "edit");

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;

    public PageCollaborationService(DaoGatewayClient dao, ResponseShapeService shape) {
        this.dao = dao;
        this.shape = shape;
    }

    // ---- brand side (G.2) -----------------------------------------------

    /** Who can currently edit this page. */
    public JsonNode list(UUID brandId, UUID templateId) {
        requireOwnedPage(brandId, templateId);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("landingTemplateId", templateId.toString());
        JsonNode rows = dao.get("/landing-page-collaborators", q);
        return rows == null ? shape.objectMapper().createArrayNode() : rows;
    }

    /**
     * Invite a creator to co-edit (G.2).
     *
     * <p>Refused unless the creator holds a confirmed link to this brand. That check is the
     * whole security model of this phase: without it, a brand could grant page access to any
     * portal identity, including creators who have never agreed to work with them.
     */
    public JsonNode invite(UUID brandId, UUID templateId, UUID creatorIdentityId,
                           String rights, UUID grantedByUserId) {
        requireOwnedPage(brandId, templateId);

        String normalized = rights == null ? "edit" : rights.trim().toLowerCase(Locale.ROOT);
        if (!RIGHTS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rights must be 'comment' or 'edit'. A collaborator cannot be granted publish — "
                            + "releasing a page to a domain or a social account stays with the brand.");
        }
        requireConfirmedLink(brandId, creatorIdentityId);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("landingTemplateId", templateId.toString());
        body.put("brandId", brandId.toString());
        body.put("creatorIdentityId", creatorIdentityId.toString());
        body.put("rights", normalized);
        if (grantedByUserId != null) {
            body.put("grantedByUserId", grantedByUserId.toString());
        }
        return dao.post("/landing-page-collaborators", body);
    }

    /** Revoke access. The row stays, marked revoked, so the history of access survives. */
    public JsonNode revoke(UUID brandId, UUID collaboratorId, UUID revokedByUserId) {
        JsonNode row = findCollaborator(collaboratorId);
        if (row == null || !row.path("brandId").asText("").equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Collaborator not found");
        }
        // The DAO's delete takes no query map, so the attribution rides on the URL. Revoking
        // marks the row rather than removing it, so who had access and when survives.
        String path = "/landing-page-collaborators/" + collaboratorId
                + (revokedByUserId == null ? "" : "?revokedByUserId=" + revokedByUserId);
        dao.delete(path);
        return shape.objectMapper().createObjectNode().put("revoked", true);
    }

    // ---- creator side (G.3) ---------------------------------------------

    /**
     * The pages this creator identity may edit.
     *
     * <p>Each entry re-checks the confirmed link. A revoked identity link therefore removes page
     * access immediately without needing a second revocation to be issued — which is the point
     * of hanging access off the link rather than duplicating it.
     */
    public JsonNode pagesForCreator(UUID creatorIdentityId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("creatorIdentityId", creatorIdentityId.toString());
        JsonNode grants = dao.get("/landing-page-collaborators", q);

        ArrayNode out = shape.objectMapper().createArrayNode();
        if (grants == null || !grants.isArray()) {
            return out;
        }
        for (JsonNode grant : grants) {
            UUID brandId = UUID.fromString(grant.get("brandId").asText());
            if (!hasConfirmedLink(brandId, creatorIdentityId)) {
                // The brand revoked the underlying relationship. Access goes with it.
                continue;
            }
            JsonNode page = dao.get("/landing-templates/" + grant.get("landingTemplateId").asText(), null);
            if (page == null) {
                continue;
            }
            // OP-18. The confirmed-link check above validated the creator against the brand named
            // on the GRANT, and this compares that brand to the one on the PAGE. Without it the
            // grant is self-certifying: a row whose brandId disagreed with its landingTemplateId
            // would pass a link check against a brand the creator legitimately works with, and
            // then hand back a page belonging to a brand they do not.
            if (!brandId.toString().equals(page.path("brandId").asText(""))) {
                continue;
            }
            ObjectNode entry = shape.objectMapper().createObjectNode();
            entry.set("page", shape.landingTemplate(page));
            entry.put("rights", grant.path("rights").asText("edit"));
            entry.put("collaboratorId", grant.path("id").asText());
            out.add(entry);
        }
        return out;
    }

    /**
     * Save a page as a collaborating creator (G.3).
     *
     * <p>Deliberately narrower than the brand-side save. A collaborator may change the page
     * CONTENT and nothing else: not its status, not its stage, not its slug. Publishing is a
     * brand action, and letting a collaborator set {@code status} would route around that.
     */
    public JsonNode saveAsCollaborator(UUID creatorIdentityId, UUID templateId, ObjectNode payload) {
        JsonNode grant = requireEditRights(creatorIdentityId, templateId);
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        // OP-18. requireEditRights validated the creator against the brand on the GRANT; this is
        // the other half — that the grant's brand is also the page's. The check cannot live inside
        // requireEditRights because that method does not fetch the page, and making it do so would
        // read the row twice on every save. See requireEditRights for the full reasoning.
        requireGrantMatchesPage(grant, page);

        // PR-40. A caller-supplied stage was previously ignored in silence, which is safe but
        // teaches nothing: a client sending one believes it worked. Refusing explicitly means the
        // one place a creator could try to move a page says why, and says it the same way for
        // every endpoint added later.
        String requestedStage = payload.path("stage").asText(null);
        if (requestedStage != null && !requestedStage.isBlank()) {
            assertCreatorStageTransition(page.path("stage").asText("draft"), requestedStage);
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        // Identity fields come from the STORED page, never from the caller.
        body.put("brandId", page.get("brandId").asText());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        body.put("name", page.path("name").asText("Landing page"));
        // Status and stage are carried over unchanged — a collaborator cannot publish.
        body.put("status", page.path("status").asText("draft"));
        body.put("stage", page.path("stage").asText("draft"));

        // Only the content moves.
        JsonNode document = payload.get("document");
        if (document != null && !document.isNull()) {
            try {
                body.put("document", shape.objectMapper().writeValueAsString(document));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unreadable document");
            }
        }
        JsonNode blocks = payload.get("blocks");
        if (blocks != null && blocks.isArray()) {
            try {
                body.put("blocks", shape.objectMapper().writeValueAsString(blocks));
            } catch (Exception ignored) {
                // Leave blocks alone rather than failing the save.
            }
        }
        // PR-39. Without this a collaborator's save was accepted and then had no effect: the
        // section editor is what production serves, so `sections` is the ONLY column a creator
        // actually edits, and it was the one column this method did not forward. The DAO
        // null-guards the field, so the edit was not destroyed — it was silently ignored, which
        // is worse to diagnose than an error, because the save returned 200 and the page simply
        // kept its old content.
        JsonNode sections = payload.get("sections");
        if (sections != null && sections.isArray()) {
            try {
                body.put("sections", shape.objectMapper().writeValueAsString(sections));
            } catch (Exception ignored) {
                // Leave sections alone rather than failing the save, matching `blocks` above.
            }
        }
        LandingTemplateWrites.carryForwardScheduledPublish(page, body);
        // OP-18. The version the CALLER claims to have been editing, not the one just read above:
        // sending the freshly-read value would compare the row against itself and agree every
        // time, which is a conflict check that can never fire. Absent when the client sends none,
        // and the DAO then allows the write — see requireCurrentVersion for why that is the trade.
        JsonNode claimedVersion = payload.get("version");
        if (claimedVersion != null && claimedVersion.isNumber()) {
            body.put("version", claimedVersion.asLong());
        }

        JsonNode saved = dao.put("/landing-templates/" + templateId, body);
        snapshotVersion(page, saved, grant);
        return shape.landingTemplate(saved);
    }

    // ---- access checks ---------------------------------------------------

    /**
     * The grant that lets this creator edit this page, or a 404.
     *
     * <p>404 rather than 403 throughout: a creator poking at page ids should not be able to
     * learn which ones exist.
     *
     * <p><b>This answers half the question, and the caller must answer the other half.</b> The
     * confirmed-link check below validates the creator against the brand named on the GRANT ROW.
     * It does not — and from here cannot — check that the grant's brand is the same as the brand
     * that owns the page, because this method deliberately does not fetch the page. So a grant row
     * whose {@code brandId} disagreed with its {@code landingTemplateId} would be self-certifying:
     * it would pass a link check against a brand the creator genuinely works with, and authorise a
     * page belonging to a brand they do not.
     *
     * <p>Callers that hold the page must therefore pass both to {@link #requireGrantMatchesPage}.
     * Splitting it this way rather than fetching the page here keeps the read out of the paths
     * that only need the rights, and it is why the obligation is written down rather than implied.
     */
    public JsonNode requireEditRights(UUID creatorIdentityId, UUID templateId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("landingTemplateId", templateId.toString());
        q.put("creatorIdentityId", creatorIdentityId.toString());
        JsonNode rows = dao.get("/landing-page-collaborators", q);
        if (rows == null || !rows.isArray() || rows.size() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        JsonNode grant = rows.get(0);
        if (!"edit".equalsIgnoreCase(grant.path("rights").asText(""))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have comment access to this page, not edit access.");
        }
        // Re-check the underlying relationship on every edit, not just at invite time.
        UUID brandId = UUID.fromString(grant.get("brandId").asText());
        if (!hasConfirmedLink(brandId, creatorIdentityId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        return grant;
    }

    /**
     * The only stage changes a creator may cause, as one allowlist that defaults to deny (PR-40).
     *
     * <p><b>Why this is central rather than restated per endpoint.</b> The creator surface is
     * about to grow from two handlers to eight or nine. A rule enforced at each one is a rule that
     * holds only while every author remembers it, and the failure mode is silent — a handler that
     * forgets it does not misbehave visibly, it just quietly lets a creator move a page somewhere
     * they should not. The same reasoning as
     * {@code CreatorTokenAuthenticationFilter}: make forgetting fail closed.
     *
     * <p><b>{@code PUBLISHED} is unreachable unconditionally</b>, not merely absent from the map.
     * Publishing is the one action that puts a brand's page in front of the world, it requires
     * {@code content:publish} which no creator holds, and {@code ck_collaborators_rights} has no
     * {@code publish} value — so this is the fourth independent guard on the same act. That is
     * deliberate: the cost of the check is nothing and the cost of being wrong is a creator
     * publishing a brand's unfinished campaign.
     *
     * <p>The only move a creator makes is the hand-back, which changes the TURN and not the stage
     * at all — so in practice this method exists to refuse everything else.
     */
    void assertCreatorStageTransition(String from, String to) {
        String current = from == null ? "" : from.trim().toLowerCase(Locale.ROOT);
        String target = to == null ? "" : to.trim().toLowerCase(Locale.ROOT);

        if (target.isEmpty() || target.equals(current)) {
            // Not a transition. A save that restates the stage it already has is the normal case.
            return;
        }
        if (LandingStageMachine.PUBLISHED.equals(target)
                || LandingStageMachine.PERFORMANCE_TRACKING.equals(target)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Publishing is the brand's decision. Send the page back when you are ready "
                            + "and they will publish it.");
        }
        // Everything else: deny by default. A creator has no legitimate reason to move the stage
        // -- handing back moves the turn instead -- so there is nothing to allow here yet. If a
        // future flow needs one, add it explicitly rather than widening the check.
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "A collaborator cannot change the stage of a page.");
    }

    /**
     * The grant's brand must be the page's brand (OP-18).
     *
     * <p>The pairing of a grant with a page is only trustworthy if both name the same brand. A
     * mismatch means the grant is describing a relationship that does not apply to this page, and
     * the only safe reading of that is no access — never "the grant says so, therefore yes".
     */
    private void requireGrantMatchesPage(JsonNode grant, JsonNode page) {
        String grantBrand = grant.path("brandId").asText("");
        if (grantBrand.isEmpty() || !grantBrand.equals(page.path("brandId").asText(""))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
    }

    private boolean hasConfirmedLink(UUID brandId, UUID creatorIdentityId) {
        JsonNode links = dao.get("/creator-identities/" + creatorIdentityId + "/links", null);
        if (links == null || !links.isArray()) {
            return false;
        }
        for (JsonNode link : links) {
            if (brandId.toString().equals(link.path("brandId").asText())
                    && "confirmed".equalsIgnoreCase(link.path("status").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private void requireConfirmedLink(UUID brandId, UUID creatorIdentityId) {
        if (!hasConfirmedLink(brandId, creatorIdentityId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This creator has no confirmed relationship with your brand. Approve their claim "
                            + "(or invite them) before granting page access.");
        }
    }

    private JsonNode requireOwnedPage(UUID brandId, UUID templateId) {
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null || !page.hasNonNull("brandId")
                || !page.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        return page;
    }

    private JsonNode findCollaborator(UUID collaboratorId) {
        // A single-row read. The earlier version listed with an empty filter, which the DAO
        // answers with an empty array on purpose (an unfiltered list would be a cross-tenant
        // leak) — so the tenancy check always failed and revoke returned 404 for everyone.
        try {
            return dao.get("/landing-page-collaborators/" + collaboratorId, null);
        } catch (RuntimeException e) {
            // A miss surfaces as a 502 from the gateway (the DAO throws), which is the same
            // outcome as not found for the caller: 404, never a leak that the id exists.
            return null;
        }
    }

    /**
     * Version the collaborator's save (A.5).
     *
     * <p>This is what makes co-editing safe without a CRDT: an overwrite by either side is
     * recoverable, which is the whole argument for deferring simultaneous editing (G.6).
     *
     * <p><b>The snapshot is of {@code before}, not {@code saved}</b> (OP-18). It used to copy the
     * post-save content, which reads as an ordinary off-by-one and is not: a history made of
     * what is already current recovers nothing. The row a collaborator needs back is precisely
     * the one their save replaced, so writing the new content into history left the overwrite
     * unrecoverable while the feature looked present — a version list filled up, every entry a
     * duplicate of the live page. {@code before} was already being passed in and ignored.
     *
     * <p>{@code sections} is versioned alongside the older shapes for the same reason it had to be
     * forwarded on save: since PR-39 it is the column a creator actually edits, so a history
     * without it protects only the parts nobody is changing.
     */
    private void snapshotVersion(JsonNode before, JsonNode saved, JsonNode grant) {
        try {
            ObjectNode version = shape.objectMapper().createObjectNode();
            // Identity still comes from `saved` — it is the same row either way, and `saved` is
            // guaranteed non-null here whereas a caller could in principle pass a null `before`.
            version.put("landingTemplateId", saved.get("id").asText());
            version.put("brandId", saved.get("brandId").asText());
            version.put("name", before.path("name").asText("Landing page"));
            version.put("stage", before.path("stage").asText("draft"));
            for (String field : new String[]{"document", "blocks", "theme", "sections"}) {
                JsonNode node = before.get(field);
                if (node != null && !node.isNull()) {
                    version.put(field, node.isTextual() ? node.asText()
                            : shape.objectMapper().writeValueAsString(node));
                }
            }
            dao.post("/landing-template-versions", version);
        } catch (Exception ignored) {
            // History is auxiliary; never fail a collaborator's save for it.
        }
    }
}
