package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Tells a creator their page went live (roadmap PR-44).
 *
 * <p>This is the acknowledgement the creator handoff was asked for. It matters more than its size
 * suggests: a creator who wrote copy for a brand and then heard nothing has no way to know whether
 * their work was used, changed beyond recognition, or quietly dropped. The link is the whole
 * payload — they want to see the thing.
 *
 * <p><b>Separate from the publish path for the same reason {@link HostingExpiryEmail} is separate
 * from its scheduler:</b> deciding <em>when</em> to send and deciding <em>what it says</em> are
 * different jobs, and only the second is a product decision.
 *
 * <p><b>Text-only.</b> The page name and the brand name are both user-supplied and would need
 * escaping in an HTML body; a plain link gives up nothing that matters here.
 *
 * <p><b>It thanks them and gets out of the way.</b> No metrics, no "share this with your audience",
 * no next-step prompt. Asking a creator to promote the page in the same breath as telling them it
 * exists reads as the brand extracting one more thing — and the share kit (PR-45) is where that
 * conversation belongs, on a screen they chose to open.
 */
public final class CreatorPublishedEmail {

    private CreatorPublishedEmail() {
    }

    /**
     * Builds the notification.
     *
     * @param to        the creator's email
     * @param brandName the brand that published it, or null if unknown
     * @param pageName  the page's name, or null
     * @param pageUrl   the public URL, or null when the page has no reachable address yet
     */
    public static EmailPort.Message compose(String to, String brandName, String pageName,
                                            String pageUrl) {
        String brand = blankTo(brandName, "The brand you worked with");
        String page = blankTo(pageName, "the campaign page");

        String subject = brandName == null || brandName.isBlank()
                ? "Your campaign page is live"
                : brand + " published the page you worked on";

        StringBuilder body = new StringBuilder();
        body.append("Good news — ").append(page).append(" is live.\n\n");
        body.append(brand).append(" has published the campaign page you helped write.\n\n");

        if (pageUrl != null && !pageUrl.isBlank()) {
            body.append("See it here:\n").append(pageUrl.trim()).append("\n\n");
        } else {
            // Deliberately not a placeholder or a dashboard link. A page can be published to a
            // custom domain that is still resolving, and a link that 404s is worse than no link:
            // the creator concludes their work was pulled.
            body.append("The brand will be able to share the link with you.\n\n");
        }

        body.append("Thank you for the work you put into it.\n");

        return EmailPort.Message.text(to, subject, body.toString());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
