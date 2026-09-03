package com.influencer.webe.content.application;

/**
 * How a creator gets a page onto their own handle (roadmap PR-45).
 *
 * <p><b>THREE outcomes, not two, and this is the design decision.</b> A binary real-or-manual
 * result forecloses the path that is actually reachable today: TikTok's {@code share/upload} and
 * Instagram's draft flow deliver the asset and caption into the creator's own composer, where they
 * tap Post. That is neither "we posted it" nor "here, do it yourself" — it is a third thing, and a
 * port that cannot say so would force every future adapter to lie in one direction or the other.
 *
 * <p><b>A failed publish must surface as a failure.</b> {@code SocialPlatformRegistry.find()}
 * returns empty on {@code !isConfigured()} and falls through to fabricated metrics by design —
 * correct for read-only vetting, and a liar the moment a publish path shares that credential.
 * Nothing here may degrade to simulation: only a failed READ may do that. Hence
 * {@link Outcome#FAILED} rather than a quiet fallback to {@link Outcome#MANUAL}.
 *
 * <p><b>The default implementation posts nothing</b>, exactly like {@code EmailPort}'s log provider
 * and {@code BillingProvider}'s manual one. PR-45 ships the share kit — the assets, the caption, the
 * tracked link, the disclosure — and no platform adapter. Those are PR-46, gated on Meta and TikTok
 * approvals that are somebody else's clock.
 */
public interface SocialPublishPort {

    /** What happened, from the creator's point of view rather than the API's. */
    enum Outcome {
        /** The platform accepted it and it is live. Only an approved adapter may return this. */
        POSTED,

        /**
         * The asset and caption are in the creator's composer or drafts, waiting for them to tap
         * Post.
         *
         * <p>This is the outcome PR-45 exists to make expressible. It removes the two worst steps
         * on mobile — downloading an 8MB video over cellular data and re-uploading it — without
         * needing the publishing permission that takes weeks of review.
         */
        STAGED_FOR_USER_CONFIRMATION,

        /**
         * Nothing was sent anywhere; the creator has everything they need to post by hand.
         *
         * <p>The shipped default, and not a consolation prize: the kit carries correctly-sized
         * assets, a caption written from the page's own words, the tracked link and a
         * non-removable disclosure. Most creators post from their phone anyway.
         */
        MANUAL,

        /** An adapter tried and could not. Never a silent downgrade to MANUAL — see the class note. */
        FAILED
    }

    /**
     * The result of an attempt.
     *
     * @param outcome what happened
     * @param platform which platform this concerned, for the message the creator is shown
     * @param detail   a human-readable reason; required when {@link Outcome#FAILED}, so a failure
     *                 can never reach a creator as a shrug
     */
    record Result(Outcome outcome, String platform, String detail) {

        public static Result manual(String platform) {
            return new Result(Outcome.MANUAL, platform, null);
        }

        public static Result failed(String platform, String detail) {
            return new Result(Outcome.FAILED, platform,
                    detail == null || detail.isBlank() ? "The platform did not accept the post." : detail);
        }

        public boolean isFailure() {
            return outcome == Outcome.FAILED;
        }
    }

    /** Which platform this implementation serves — {@code instagram}, {@code tiktok}, {@code manual}. */
    String platform();

    /**
     * Attempt to publish, or say honestly that nothing was sent.
     *
     * <p>Never throws: an implementation that cannot reach its platform returns
     * {@link Result#failed}, for the same reason {@code PageGenerationPort} never throws — the
     * creator is standing in front of a share sheet that already has everything they need by hand.
     */
    Result publish(String caption, String assetUrl, String linkUrl);
}
