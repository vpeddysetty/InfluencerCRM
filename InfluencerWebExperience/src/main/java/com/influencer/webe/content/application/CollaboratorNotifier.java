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
}
