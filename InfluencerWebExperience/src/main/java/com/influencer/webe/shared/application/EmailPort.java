package com.influencer.webe.shared.application;

/**
 * Outbound transactional email (roadmap M1.3).
 *
 * <p>A port rather than a direct provider call, for the same reason as {@link AssetStoragePort}:
 * the provider is an operational decision that changes, and local development has no mail server.
 * Postmark, SES and a log-only local implementation must all be reachable without any caller
 * knowing which is active.
 *
 * <p><b>Why this exists at all.</b> Before it, there was no email capability anywhere in the
 * codebase — no {@code JavaMailSender}, no starter-mail dependency, no SendGrid, Postmark, Mailgun
 * or SES. The member-invitation flow was complete on both ends except that nothing sent: the UI
 * rendered a one-time token on screen with the instruction "send this to the invitee". That made a
 * collaboration product's invite flow depend on the inviter's own email client.
 *
 * <p><b>Lives in {@code shared} deliberately.</b> Identity sends invitations today; content will
 * send expiry warnings (M5.6) and creator welcome packages (M8.4). A port owned by one context and
 * borrowed by another is the dependency arrow the ArchUnit boundary tests exist to prevent.
 *
 * <p><b>Delivery is best-effort and never blocks its caller.</b> Implementations must not throw on
 * a provider failure — see {@link #send}. An invitation that was created must not be rolled back
 * because a mail server was briefly unreachable; the token still exists and can be resent.
 */
public interface EmailPort {

    /**
     * A message to send.
     *
     * @param to        recipient address, already validated by the caller
     * @param subject   subject line
     * @param textBody  plain-text body — always required
     * @param htmlBody  optional HTML body; null sends text-only
     */
    record Message(String to, String subject, String textBody, String htmlBody) {

        /** Text-only message. The safe default: no HTML means no HTML injection surface. */
        public static Message text(String to, String subject, String body) {
            return new Message(to, subject, body, null);
        }
    }

    /**
     * The outcome of an attempt.
     *
     * @param sent       whether the provider accepted the message
     * @param provider   which implementation handled it — {@code "log"}, {@code "postmark"}, …
     * @param detail     provider message id when sent, or the failure reason when not
     */
    record Result(boolean sent, String provider, String detail) {

        public static Result sent(String provider, String messageId) {
            return new Result(true, provider, messageId);
        }

        public static Result failed(String provider, String reason) {
            return new Result(false, provider, reason);
        }
    }

    /**
     * Attempt delivery.
     *
     * <p><b>Must not throw.</b> Callers treat email as best-effort: an invitation is already
     * persisted by the time this runs, and a provider outage must not fail the request that
     * created it. Implementations report failure through {@link Result#sent()} instead, so a
     * caller that does care can check, and one that does not is not forced into a try/catch.
     */
    Result send(Message message);

    /**
     * Which implementation is active.
     *
     * <p>Exposed so a diagnostics surface can state the provider rather than imply one. The same
     * reasoning as {@code metrics_source} on creator metrics and {@code provider} on the domain
     * registrar: a log-only implementation that claims to have sent mail is worse than one that
     * says plainly that it did not.
     */
    String provider();
}
