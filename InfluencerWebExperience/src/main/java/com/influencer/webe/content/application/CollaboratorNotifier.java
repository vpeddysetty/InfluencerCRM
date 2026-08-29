package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.shared.application.CreatorDirectory;
import com.influencer.webe.shared.application.EmailPort;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tells the creators who worked on a page that it went live (roadmap PR-44).
 *
 * <p><b>Nothing here may fail a publish.</b> Every path is wrapped: a page that published but whose
 * notification did not send is a missed email, while a publish refused because an email failed is a
 * launch that did not happen. The same trade {@code snapshotVersion} and the hosting-window stamp
 * both make, and for the same reason — the operation the user asked for outranks the bookkeeping
 * around it.
 *
 * <p><b>It reaches the creator context through a port, not directly.</b> {@code content} may not
 * import {@code identity}'s infrastructure — see the boundary rules — so the email addresses come
 * from {@link CreatorDirectory}, which {@code identity} implements. That inversion is also what
 * keeps the address projection in one place: the DAO's creator endpoint returns a password hash,
 * and only the identity context should ever be handling that.
 */
@Service
public class CollaboratorNotifier {

    private static final Logger log = LoggerFactory.getLogger(CollaboratorNotifier.class);

    private final DaoGatewayClient dao;
    private final CreatorDirectory creators;
    private final EmailPort emailPort;
    private final String publicBaseUrl;

    public CollaboratorNotifier(DaoGatewayClient dao,
                                CreatorDirectory creators,
                                EmailPort emailPort,
                                @Value("${web-experience.public-base-url:}") String publicBaseUrl,
                                @Value("${web-experience.ui-base-url:}") String uiBaseUrl) {
        this.dao = dao;
        this.creators = creators;
        this.emailPort = emailPort;
        // FIRST value only, trailing slash stripped. ui-base-url may be a comma-separated list
        // because the same site is served from several hostnames and CORS must allow them all;
        // MemberInvitationService learned live that using the whole string produces
        // "https://a.com,https://b.com/s/slug" — not a link.
        String configured = publicBaseUrl == null || publicBaseUrl.isBlank() ? uiBaseUrl : publicBaseUrl;
        this.publicBaseUrl = configured == null ? "" : configured.split(",")[0].trim().replaceAll("/+$", "");
    }

    /**
     * Notify every creator who holds a live grant on this page.
     *
     * <p>Called only on FIRST publish — see {@code LandingStageService}. Republishing after an edit
     * does not re-send, because a creator who received three identical "your page is live" emails
     * would learn to ignore the first one.
     */
    public void notifyPublished(UUID brandId, UUID templateId) {
        try {
            JsonNode page = dao.get("/landing-templates/" + templateId, null);
            if (page == null) {
                return;
            }

            Map<String, String> query = new LinkedHashMap<>();
            query.put("landingTemplateId", templateId.toString());
            JsonNode grants = dao.get("/landing-page-collaborators", query);
            if (grants == null || !grants.isArray() || grants.isEmpty()) {
                // The ordinary case: most pages have no creator on them. Not worth a log line.
                return;
            }

            String pageUrl = publicUrl(page);
            String pageName = page.path("name").asText(null);
            String brandName = brandName(brandId);

            // De-duplicated by identity: a creator granted access twice — invited, revoked,
            // re-invited — has two rows and must still receive one email.
            Set<String> notified = new LinkedHashSet<>();
            for (JsonNode grant : grants) {
                if (grant.hasNonNull("revokedAt")) {
                    // Deliberate. Somebody whose access was withdrawn before the page went live was
                    // taken off the work, and telling them it shipped is at best confusing.
                    continue;
                }
                String identityId = grant.path("creatorIdentityId").asText(null);
                if (identityId == null || !notified.add(identityId)) {
                    continue;
                }
                sendOne(UUID.fromString(identityId), brandName, pageName, pageUrl);
            }
        } catch (RuntimeException e) {
            // The publish already happened. Losing the notification is a missed email; letting this
            // escape would turn a successful launch into a failed request.
            log.warn("Publish notification failed for page {}: {}", templateId, e.toString());
        }
    }

    private void sendOne(UUID creatorIdentityId, String brandName, String pageName, String pageUrl) {
        try {
            Optional<CreatorDirectory.Creator> creator = creators.lookupCreator(creatorIdentityId);
            if (creator.isEmpty() || creator.get().email() == null || creator.get().email().isBlank()) {
                return;
            }
            EmailPort.Result result = emailPort.send(CreatorPublishedEmail.compose(
                    creator.get().email(), brandName, pageName, pageUrl));
            if (!result.sent()) {
                // The port REPORTS failure rather than throwing it, and the `log` provider — the
                // configured default today — returns sent=false having written a line. Checking the
                // result rather than only catching exceptions is what stops this reporting success
                // for mail nobody sent.
                log.info("Publish notification not delivered to creator {} via {}: {}",
                        creatorIdentityId, result.provider(), result.detail());
            }
        } catch (RuntimeException e) {
            // One creator's bad address must not stop the others being told.
            log.warn("Publish notification failed for creator {}: {}", creatorIdentityId, e.toString());
        }
    }

    /**
     * The page's public address, or null when it has none yet.
     *
     * <p>Null rather than a guess: a link that 404s is worse than no link, because the creator
     * concludes their work was pulled.
     */
    private String publicUrl(JsonNode page) {
        String slug = page.path("publicSlug").asText(null);
        if (slug == null || slug.isBlank() || publicBaseUrl.isEmpty()) {
            return null;
        }
        return publicBaseUrl + "/s/" + slug;
    }

    private String brandName(UUID brandId) {
        try {
            JsonNode brand = dao.get("/brands/" + brandId, null);
            return brand == null ? null : brand.path("name").asText(null);
        } catch (RuntimeException e) {
            // The email reads fine without it — "The brand you worked with" — so an unavailable
            // name is not a reason to skip the notification entirely.
            return null;
        }
    }

    /**
     * Tell the brand a creator has sent their page back (roadmap PR-44).
     *
     * <p>The return leg of the handoff, and the one email that has a deadline attached: the creator
     * has stopped work and is waiting. Without it the page sits in the brand's "waiting on you"
     * list, which nobody watches until they happen to open the app.
     *
     * <p>Sent to the user who granted the access rather than to every member of the account. They
     * asked for this work; a broadcast to the whole team would train everyone to ignore it, which
     * is how the one person who cares stops seeing it too.
     */
    public void notifyHandedBack(UUID brandId, UUID templateId, UUID creatorIdentityId, String note) {
        try {
            JsonNode page = dao.get("/landing-templates/" + templateId, null);
            if (page == null) {
                return;
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("landingTemplateId", templateId.toString());
            query.put("creatorIdentityId", creatorIdentityId.toString());
            JsonNode grants = dao.get("/landing-page-collaborators", query);
            if (grants == null || !grants.isArray() || grants.isEmpty()) {
                return;
            }

            String grantedBy = grants.get(0).path("grantedByUserId").asText(null);
            if (grantedBy == null) {
                // Pre-PR-42 grants carry no attribution. Nobody to tell, and guessing at an
                // account member would send it to somebody who never asked for the work.
                return;
            }
            JsonNode user = dao.get("/users/" + grantedBy, null);
            String to = user == null ? null : user.path("email").asText(null);
            if (to == null || to.isBlank()) {
                return;
            }

            String creatorName = creators.lookupCreator(creatorIdentityId)
                    .map(CreatorDirectory.Creator::displayName)
                    .orElse(null);

            EmailPort.Result result = emailPort.send(CreatorHandedBackEmail.compose(
                    to, creatorName, page.path("name").asText(null), note, manageUrl(templateId)));
            if (!result.sent()) {
                log.info("Hand-back notification not delivered to {} via {}: {}",
                        to, result.provider(), result.detail());
            }
        } catch (RuntimeException e) {
            // The hand-back already happened. A missed email is a worse experience; a failed
            // hand-back would leave the creator unable to return work they have finished.
            log.warn("Hand-back notification failed for page {}: {}", templateId, e.toString());
        }
    }

    /** Deep link into the page in the brand's own app, or null when no UI base is configured. */
    private String manageUrl(UUID templateId) {
        return publicBaseUrl.isEmpty() ? null : publicBaseUrl + "/content?page=" + templateId;
    }
}
