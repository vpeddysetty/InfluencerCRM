package com.influencer.webe.creator.application;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads creator profiles from a social platform, from the BFF's side of the boundary
 * (roadmap C.1).
 *
 * <p>This returns <b>facts only</b> — counts, engagement, verification. Niche, themes and risk
 * are classified separately by the agent service. Keeping them apart is the point of Phase C:
 * a model asked for a follower count returns a confident, plausible, wrong number.
 *
 * <p>Implementations must report {@link Profile#source()} honestly. {@code platform_api} means
 * a real platform answered; a simulated adapter says {@code mock} and the value is written to
 * the creator row, so a brand can always tell measured from invented.
 */
public interface SocialProfileGateway {

    /**
     * Look up a handle.
     *
     * @return the profile, or null when it cannot be resolved. Null is expected — private
     *         accounts, typos, deleted profiles, an unapproved app — and callers must degrade
     *         to the manual path rather than failing the signup (C.6).
     */
    Profile fetch(String platform, String handle);

    record Profile(
            String handle,
            String platform,
            /** Provenance, stored verbatim: platform_api | mock | manual | import. */
            String source,
            Long followerCount,
            java.math.BigDecimal engagementRate,
            Long averageViews,
            Boolean verified,
            String lastActiveAt,
            JsonNode demographics,
            String displayName,
            String recentCaptions
    ) {}
}
