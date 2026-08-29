package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PageCollaborationService.class);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final HandoffMachine handoffMachine;
    private final LandingService landingService;
    private final CampaignPageGenerationService generation;
    private final CollaboratorNotifier notifier;

    public PageCollaborationService(DaoGatewayClient dao, ResponseShapeService shape,
                                    HandoffMachine handoffMachine,
                                    LandingService landingService,
                                    CampaignPageGenerationService generation,
                                    CollaboratorNotifier notifier) {
        this.dao = dao;
        this.shape = shape;
        this.handoffMachine = handoffMachine;
        this.landingService = landingService;
        this.generation = generation;
        this.notifier = notifier;
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

    /**
     * Hand a page to a creator: one button, one endpoint (roadmap PR-42).
     *
     * <p><b>Why this is one operation and not three calls from the UI.</b> A handoff is a grant, a
     * stage change and a turn change that only mean anything together. Done from the client, a
     * failure between them leaves a page in a state nobody designed: a grant with no stage change
     * is access to a page the board still shows as the brand's, and a stage change with no grant
     * is a page marked "with the creator" that the creator cannot open. Neither is recoverable by
     * retrying, because the second attempt sees the half-done first one.
     *
     * <p>Ordered so the reversible parts happen first. The grant is written before the stage moves,
     * because a grant with no stage change is invisible but harmless, while a stage change with no
     * grant is visible and wrong — it tells the brand they are waiting on somebody who was never
     * asked.
     *
     * @param note optional message from the brand, stored with the handoff rather than emailed
     *             separately, so "what did they ask for?" survives in one place
     */
    public JsonNode handOff(UUID brandId, UUID templateId, UUID creatorIdentityId,
                            UUID grantedByUserId, String note) {
        JsonNode page = requireOwnedPage(brandId, templateId);

        String stage = page.path("stage").asText(LandingStageMachine.DRAFT);
        if (!handoffMachine.canHandOff(stage)) {
            // Refused BEFORE the grant is written, so a rejected handoff leaves no collaborator row
            // behind. An orphaned grant would give a creator access to a page nobody handed them.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A page at stage '" + stage + "' cannot be handed to a creator. "
                            + "Approve it first.");
        }

        // The grant, with its own confirmed-link check. Rights are always `edit` for v1: `comment`
        // is accepted by the schema and has no client that can honour it, and shipping a grant
        // nothing implements is worse than recording the gap.
        JsonNode grant = invite(brandId, templateId, creatorIdentityId, "edit", grantedByUserId);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        body.put("name", page.path("name").asText("Landing page"));
        body.put("status", page.path("status").asText("draft"));
        body.put("stage", LandingStageMachine.CREATOR_ASSIGNED);
        body.put("turn", HandoffMachine.CREATOR);
        body.put("turnChangedAt", Instant.now().toString());
        // The same obligation every partial write to this row carries — see LandingTemplateWrites.
        LandingTemplateWrites.carryForwardScheduledPublish(page, body);
        JsonNode updated = dao.put("/landing-templates/" + templateId, body);

        recordHandoff(brandId, templateId, HandoffMachine.CREATOR, grantedByUserId, null, note);

        ObjectNode response = shape.objectMapper().createObjectNode();
        response.set("page", shape.landingTemplate(updated));
        response.set("collaborator", grant);
        return response;
    }

    /**
     * Take the page back from the creator (roadmap PR-42).
     *
     * <p>Deliberately allowed even when the turn already reads {@code brand} — see
     * {@link HandoffMachine#canTakeBack}. This is how a brand recovers from an accidental handoff
     * or an unresponsive creator, so refusing it in the state where the two have drifted apart
     * would block the exact case it exists for.
     *
     * <p>The collaborator grant is left alone. Taking a turn back is not revoking access, and
     * conflating them would mean a brand who wanted the page back for an hour had to re-invite the
     * creator afterwards.
     */
    public JsonNode takeBack(UUID brandId, UUID templateId, UUID actingUserId) {
        JsonNode page = requireOwnedPage(brandId, templateId);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        body.put("name", page.path("name").asText("Landing page"));
        body.put("status", page.path("status").asText("draft"));
        body.put("stage", page.path("stage").asText(LandingStageMachine.DRAFT));
        body.put("turn", HandoffMachine.BRAND);
        body.put("turnChangedAt", Instant.now().toString());
        LandingTemplateWrites.carryForwardScheduledPublish(page, body);
        JsonNode updated = dao.put("/landing-templates/" + templateId, body);

        recordHandoff(brandId, templateId, HandoffMachine.BRAND, actingUserId, null, null);
        return shape.landingTemplate(updated);
    }

    /**
     * Render a preview for a creator (roadmap PR-44).
     *
     * <p>Delegates to the same renderer the brand's preview uses — the point is the authorisation,
     * not the rendering. The brand's endpoint requires {@code CONTENT_WRITE}, an operator
     * permission a creator provably lacks, so a creator calling it would be refused for a reason
     * that has nothing to do with whether they may see this page.
     *
     * <p>The grant is re-checked here rather than trusted from the session, so a creator cannot
     * preview a page they were removed from a moment ago.
     */
    public String previewForCreator(UUID creatorIdentityId, UUID templateId, ObjectNode payload) {
        JsonNode grant = requireEditRights(creatorIdentityId, templateId);
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        requireGrantMatchesPage(grant, page);

        ObjectNode body = payload == null ? shape.objectMapper().createObjectNode() : payload.deepCopy();
        // Identity comes from the STORED page, never the caller: a preview that rendered somebody
        // else's brand or slug would be a way to read a page through this endpoint.
        body.put("brandId", page.get("brandId").asText());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        return landingService.previewTemplate(UUID.fromString(page.get("brandId").asText()), body);
    }

    /**
     * Rewrite one section with AI, for a creator (roadmap PR-44).
     *
     * <p>Same port as the brand's rewrite, different authorisation — and one deliberate difference
     * in framing that belongs in the prompt rather than here: a creator is being helped to sound
     * like themselves, not like the brand. The section type is pinned from the request by the
     * generation service, so a rewrite cannot restructure the page.
     */
    public JsonNode rewriteSectionForCreator(UUID creatorIdentityId, UUID templateId, ObjectNode payload) {
        JsonNode grant = requireEditRights(creatorIdentityId, templateId);
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        requireGrantMatchesPage(grant, page);

        // The brand and campaign come from the STORED page, exactly as they do in
        // previewForCreator -- and here they are not only a tenancy guard but the thing that makes
        // the call work at all. `rewriteSection` builds a Brief, and a Brief with no `goal` is a
        // 400; the goal is filled in by BriefEnricher from brandId + campaignId, which a creator
        // has no way to send and must not be trusted to send. Forwarding the caller's payload
        // unchanged failed every creator rewrite with "goal is required" -- found by running the
        // editor against a live stack, because the UI sends only {section, instruction}.
        ObjectNode body = payload == null ? shape.objectMapper().createObjectNode() : payload.deepCopy();
        body.put("brandId", page.get("brandId").asText());
        body.put("campaignId", page.get("campaignId").asText());

        // A goal, or the Brief is refused with a 400 before the generator is ever asked.
        //
        // BriefEnricher fills `goal` from the CAMPAIGN BRIEF and from nowhere else, so a campaign
        // with no brief row -- which is most of them, since a brief is optional -- leaves it blank.
        // Confirmed against a live stack: every rewrite from the section editor failed this way,
        // on the brand's side as much as the creator's, because SectionEditor's onRewrite contract
        // is {section, instruction} and carries no brief at all.
        //
        // The page itself is the honest fallback. The creator is rewording THIS page, so its name
        // states the goal as well as any sentence we could invent, and stating it plainly beats
        // inventing a campaign objective the brand never wrote. Set only when the enricher would
        // find nothing, so a real brief still wins.
        if (body.path("goal").asText("").isBlank()) {
            body.put("goal", "Rewrite one section of the landing page \""
                    + page.path("name").asText("Landing page") + "\" in the creator's own voice.");
        }
        return generation.rewriteSection(body);
    }

    /**
     * The creator sends the page back to the brand (roadmap PR-44).
     *
     * <p><b>This moves the TURN and not the stage</b>, which is the whole reason the two columns
     * exist. The creator is asserting they are done; whether the page is then <i>ready to
     * publish</i> is the brand's judgement, not theirs. Advancing the stage here would let a
     * creator declare a brand's campaign finished.
     *
     * <p>Refused unless it is actually their turn. Without that check a creator could return a page
     * they were never given, or return one twice — the second click landing after the brand had
     * already picked it up, silently taking it back off them.
     */
    public JsonNode handBack(UUID creatorIdentityId, UUID templateId, String note) {
        JsonNode grant = requireEditRights(creatorIdentityId, templateId);
        JsonNode page = dao.get("/landing-templates/" + templateId, null);
        if (page == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Landing page not found");
        }
        requireGrantMatchesPage(grant, page);

        String turn = page.path("turn").asText(null);
        if (!handoffMachine.canHandBack(turn)) {
            // 409 with a reason the UI can show, rather than a silent no-op: a creator who taps
            // "send back" twice needs to know the first one worked.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This page is already back with the brand.");
        }

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", page.get("brandId").asText());
        body.put("campaignId", page.get("campaignId").asText());
        body.put("publicSlug", page.get("publicSlug").asText());
        body.put("name", page.path("name").asText("Landing page"));
        body.put("status", page.path("status").asText("draft"));
        // Unchanged, deliberately — see the note above.
        body.put("stage", page.path("stage").asText(LandingStageMachine.DRAFT));
        body.put("turn", HandoffMachine.BRAND);
        body.put("turnChangedAt", Instant.now().toString());
        LandingTemplateWrites.carryForwardScheduledPublish(page, body);
        JsonNode updated = dao.put("/landing-templates/" + templateId, body);

        UUID brandId = UUID.fromString(page.get("brandId").asText());
        recordHandoff(brandId, templateId, HandoffMachine.BRAND, null, creatorIdentityId, note);
        // The creator has stopped work and is waiting. Without this the page sits in a list nobody
        // watches until they happen to open the app.
        if (notifier != null) {
            notifier.notifyHandedBack(brandId, templateId, creatorIdentityId, note);
        }
        return shape.landingTemplate(updated);
    }

    /**
     * Write the audit row.
     *
     * <p>Never fails the operation it records. A handoff that succeeded and whose audit row did not
     * write is a reporting gap; a handoff refused because its audit row failed is a feature outage.
     * The same trade {@code snapshotVersion} makes, and for the same reason.
     */
    private void recordHandoff(UUID brandId, UUID templateId, String toTurn,
                               UUID actorUserId, UUID actorCreatorIdentityId, String note) {
        try {
            ObjectNode row = shape.objectMapper().createObjectNode();
            row.put("landingTemplateId", templateId.toString());
            row.put("brandId", brandId.toString());
            row.put("toTurn", toTurn);
            if (actorUserId != null) {
                row.put("actorUserId", actorUserId.toString());
            }
            if (actorCreatorIdentityId != null) {
                row.put("actorCreatorIdentityId", actorCreatorIdentityId.toString());
            }
            if (note != null && !note.isBlank()) {
                row.put("note", note.trim());
            }
            // Per occurrence, never templateId:from->to. Work legitimately goes round the loop
            // more than once, and V24's transition log learned the expensive way that a key
            // derived from the endpoints makes the second pass vanish from the audit trail.
            row.put("idempotencyKey", templateId + ":" + toTurn + ":" + UUID.randomUUID());
            dao.post("/page-handoffs", row);
        } catch (RuntimeException e) {
            log.warn("Handoff for page {} was applied but not recorded", templateId, e);
        }
    }

    /** Revoke access. The row stays, marked revoked, so the history of access survives. */
    public JsonNode revoke(UUID brandId, UUID collaboratorId, UUID revokedByUserId) {
        JsonNode row = findCollaborator(collaboratorId);
        if (row == null || !row.path("brandId").asText("").equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Collaborator not found");
        }
        // PR-44. Snapshot BEFORE the access is cut, so the brand keeps whatever the creator had
        // written. Revoking is usually done in a hurry — a relationship ended, or the wrong person
        // was invited — and the work in progress is the thing most easily lost in that moment. The
        // creator may have been mid-edit; this captures what was last saved, which is the most the
        // server can honestly promise.
        String templateId = row.path("landingTemplateId").asText(null);
        if (templateId != null) {
            JsonNode page = null;
            try {
                page = dao.get("/landing-templates/" + templateId, null);
            } catch (RuntimeException e) {
                log.warn("Could not read page {} before revoking access", templateId, e);
            }
            if (page != null) {
                snapshotVersion(page, page, row);
            }
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
            // PR-44. The portal names the brand on every screen -- most importantly on the button
            // that sends the page back -- and a creator working with four brands needs to know
            // WHICH one they are returning work to. The landing template carries only a brandId,
            // so without this the editor falls back to "the brand" on the one screen where the
            // distinction matters most.
            entry.put("brandName", brandName(brandId));
            out.add(entry);
        }
        return out;
    }

    /**
     * The brand's display name, for the creator-facing screens (roadmap PR-44).
     *
     * <p>Resolved here through this service's own DAO client rather than by calling
     * {@code CreatorPortalService}, which has the identical private helper. That is not duplication
     * worth removing: reaching into another context's service to read one string would make
     * {@code content} depend on {@code identity} for a label, and the boundary rules exist to stop
     * exactly that kind of incidental coupling.
     *
     * <p>Never throws. A brand whose name cannot be read is a missing label, not a reason the
     * creator cannot see their pages -- the same trade the audit-row write makes.
     */
    private String brandName(UUID brandId) {
        try {
            JsonNode brand = dao.get("/tenancy/brands/" + brandId, null);
            return brand != null && brand.hasNonNull("name") ? brand.get("name").asText() : "Brand";
        } catch (Exception exception) {
            return "Brand";
        }
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

        // PR-44. A revoked grant is answered differently from an unknown page, and the difference
        // is deliberate. Everywhere else here a refusal is a bare 404 so a creator probing ids
        // cannot learn which exist — but this creator was demonstrably given this page, so there is
        // nothing left to conceal, and a 404 would tell them their work vanished. The portal reads
        // `access_revoked` to say "the brand ended your access" and offer their draft.
        if (grant.hasNonNull("revokedAt")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_revoked");
        }

        if (!"edit".equalsIgnoreCase(grant.path("rights").asText(""))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You have comment access to this page, not edit access.");
        }
        // Re-check the underlying relationship on every edit, not just at invite time.
        UUID brandId = UUID.fromString(grant.get("brandId").asText());
        if (!hasConfirmedLink(brandId, creatorIdentityId)) {
            // The brand ended the whole relationship rather than this one page. Same answer for the
            // same reason: they held this page, so concealment buys nothing.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_revoked");
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
