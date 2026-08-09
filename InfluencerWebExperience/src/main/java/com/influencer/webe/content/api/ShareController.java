package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.content.application.ShareService;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Brand ↔ creator share links (Content collaboration Phase 1).
 *
 * - Brand-auth'd: create / list / revoke shares under /api/shares.
 * - PUBLIC (no auth): resolve a token at /api/public/shares/{token} — returns the
 *   scoped brief + read-only landing HTML for the creator to review.
 */
@RestController
public class ShareController {
    private final ShareService shareService;
    private final RequestUserResolver requestUserResolver;

    public ShareController(ShareService shareService, RequestUserResolver requestUserResolver) {
        this.shareService = shareService;
        this.requestUserResolver = requestUserResolver;
    }

    @PostMapping("/api/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        UUID userId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        return shareService.createShare(userId, payload);
    }

    @GetMapping("/api/shares")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID userId,
                         @RequestParam(required = false) UUID campaignId) {
        UUID resolved = requestUserResolver.resolveUserId(authorization, userId);
        return shareService.listShares(resolved, campaignId);
    }

    @DeleteMapping("/api/shares/{id}")
    public void revoke(@RequestHeader(value = "Authorization", required = false) String authorization,
                       @PathVariable UUID id) {
        UUID userId = requestUserResolver.resolveUserId(authorization, null);
        shareService.revokeShare(userId, id);
    }

    /** PUBLIC — no auth. Resolve a share token into its scoped, read-only payload. */
    @GetMapping("/api/public/shares/{token}")
    public JsonNode resolvePublic(@PathVariable String token) {
        return shareService.resolvePublic(token);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
