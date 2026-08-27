package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The columns a partial write to {@code landing_templates} must restate, and why.
 *
 * <p><b>Why this class exists at all.</b> The DAO's {@code PUT /landing-templates/{id}} replaces
 * the row. Most nullable columns are null-guarded there — {@code document}, {@code blocks},
 * {@code theme}, {@code sections}, {@code hostingExpiresAt}, {@code firstPublishedAt} — so a PUT
 * that omits them leaves them alone. {@code scheduledPublishAt} is deliberately <b>not</b> guarded,
 * because clearing it is how the scheduler consumes a fired publish; a guard would make that
 * unexpressible and every published page would republish on every sweep. The controller says so in
 * a comment and names the obligation it creates:
 *
 * <blockquote>every BFF caller writing this row restates it</blockquote>
 *
 * <p>That obligation was met in exactly one of the three places it applies. {@code LandingService
 * .saveTemplate} carried the value forward; {@code PageCollaborationService.saveAsCollaborator} and
 * {@code LandingStageService.changeStage} did not, so a creator saving their edits, or anyone
 * dragging a card between Kanban columns, silently cancelled a launch the brand had scheduled —
 * with no error, and nothing on screen to notice until the page failed to go live.
 *
 * <p>A rule that has now been got wrong twice is not a rule anyone will remember the third time.
 * It lives here so the next person writing a partial update has one call to make rather than a
 * comment to find, and so the reason survives next to the code instead of in a commit message.
 *
 * <p>Package-private and static: this is a shared correctness detail of the three services that
 * write this row, not a capability worth publishing from the context.
 */
final class LandingTemplateWrites {

    private LandingTemplateWrites() {
    }

    /**
     * Restate a pending scheduled publish so a partial write does not cancel it.
     *
     * @param existing the STORED row, as fetched before the write — never the caller's payload,
     *                 which is exactly the untrusted thing a partial update is trying not to honour
     * @param body     the outgoing PUT body, mutated in place
     */
    static void carryForwardScheduledPublish(JsonNode existing, ObjectNode body) {
        if (existing != null && existing.hasNonNull("scheduledPublishAt")) {
            body.put("scheduledPublishAt", existing.get("scheduledPublishAt").asText());
        }
    }
}
