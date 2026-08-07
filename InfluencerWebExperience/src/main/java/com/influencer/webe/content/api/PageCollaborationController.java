package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.PageCollaborationService;
import com.influencer.webe.identity.application.CreatorPortalService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Brand-creator co-editing (roadmap Phase G).
 *
 * <p>Two authentication schemes side by side, which is the whole point of this controller:
 * brand users hold an operator JWT, creators hold an opaque {@code X-Creator-Token} from the
 * portal. A creator is not an account member and has no role — page access comes from a
 * collaborator grant, checked against a confirmed identity link.
 *
 * <p>Notice which side can publish: neither creator endpoint touches status or stage.
 */
@RestController
public class PageCollaborationController {

    private final PageCollaborationService collaboration;
    private final CreatorPortalService creatorPortal;
    private final RequestUserResolver requestUserResolver;

    public PageCollaborationController(PageCollaborationService collaboration,
                                       CreatorPortalService creatorPortal,
                                       RequestUserResolver requestUserResolver) {
        this.collaboration = collaboration;
        this.creatorPortal = creatorPortal;
        this.requestUserResolver = requestUserResolver;
    }

    // ---- brand side (G.2) -----------------------------------------------

    @GetMapping("/api/landing-pages/{id}/collaborators")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable UUID id) {
        UUID brandId = requestUserResolver.requirePermissionForBrand(authorization, Permission.CONTENT_READ);
        return collaboration.list(brandId, id);
    }

    /**
     * Invite a confirmed creator to co-edit this page.
     *
     * <p>Requires {@code content:write} — the brand side of the relationship. Refused if the
     * creator has no confirmed link to this brand.
     */
    @PostMapping("/api/landing-pages/{id}/collaborators")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CONTENT_WRITE);
        if (!payload.hasNonNull("creatorIdentityId")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creatorIdentityId is required");
        }
        return collaboration.invite(context.brandId(), id,
                UUID.fromString(payload.get("creatorIdentityId").asText()),
                payload.path("rights").asText("edit"),
                context.userId());
    }

    @DeleteMapping("/api/landing-pages/collaborators/{collaboratorId}")
    public JsonNode revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID collaboratorId) {
        var context = requestUserResolver.requirePermission(authorization, Permission.CONTENT_WRITE);
        return collaboration.revoke(context.brandId(), collaboratorId, context.userId());
    }

    // ---- creator portal side (G.3) --------------------------------------

    /** The pages this creator may work on. Authenticated by the portal token, not a JWT. */
    @GetMapping("/api/creator-portal/pages")
    public JsonNode myPages(@RequestHeader(value = "X-Creator-Token", required = false) String token) {
        return collaboration.pagesForCreator(requireCreator(token));
    }

    /**
     * Save page content as a collaborating creator.
     *
     * <p>The same GrapesJS surface as the brand builder, scoped to pages the creator
     * collaborates on. Content only: status, stage and slug are carried over from the stored
     * page, so a collaborator cannot publish by including a field in the body.
     */
    @PutMapping("/api/creator-portal/pages/{id}")
    public JsonNode save(@RequestHeader(value = "X-Creator-Token", required = false) String token,
                         @PathVariable UUID id,
                         @RequestBody ObjectNode payload) {
        return collaboration.saveAsCollaborator(requireCreator(token), id, payload);
    }

    /**
     * Resolve the portal token to a creator identity.
     *
     * <p>401 rather than 403 on a bad token: the caller is unauthenticated, not forbidden, and
     * the distinction matters for how a client retries.
     */
    private UUID requireCreator(String token) {
        return creatorPortal.resolve(token)
                .map(session -> session.creatorIdentityId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "A creator portal session is required"));
    }
}
