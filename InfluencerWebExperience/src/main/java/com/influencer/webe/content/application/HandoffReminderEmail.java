package com.influencer.webe.content.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * The two nudges an abandoned handoff produces (roadmap PR-44).
 *
 * <p><b>Two messages to two people, for two different reasons.</b> Day three the creator is
 * reminded, because the usual cause is a forgotten email. Day seven the brand is told, because by
 * then the likely cause is that the creator is not going to do it — and the useful action, chasing
 * them or taking the page back, belongs to the brand. Reminding the creator forever would be
 * nagging somebody who has already decided.
 *
 * <p>Both are deliberately short and neither is scolding. A creator who has not started is usually
 * busy rather than avoidant, and a reminder that reads as an accusation makes the next
 * collaboration harder than the missed deadline did.
 */
public final class HandoffReminderEmail {

    private HandoffReminderEmail() {
    }

    /** Day three, to the creator. */
    public static EmailPort.Message toCreator(String to, String brandName, String pageName,
                                              String openUrl) {
        String brand = blankTo(brandName, "A brand");
        String page = blankTo(pageName, "a campaign page");

        StringBuilder body = new StringBuilder();
        body.append(brand).append(" is waiting on your changes to ").append(page).append(".\n\n");
        if (openUrl != null && !openUrl.isBlank()) {
            body.append("Pick it up here:\n").append(openUrl.trim()).append("\n\n");
        }
        // Says what to do if they cannot, because the alternative to "I have not got to it" is
        // usually silence rather than a decline -- and silence is what this sweep exists to break.
        body.append("If you are not able to work on this, let them know and they can take it back.\n");

        return EmailPort.Message.text(to, "A reminder: " + page + " is waiting for you", body.toString());
    }

    /** Day seven, to the brand. */
    public static EmailPort.Message toBrand(String to, String creatorName, String pageName,
                                            long daysWaiting, String manageUrl) {
        String creator = blankTo(creatorName, "The creator");
        String page = blankTo(pageName, "your campaign page");

        StringBuilder body = new StringBuilder();
        body.append(page).append(" has been with ").append(creator).append(" for ")
                .append(daysWaiting).append(" days with no changes.\n\n");
        body.append("They have had a reminder. You can chase them, or take the page back and "
                + "finish it yourself.\n\n");
        if (manageUrl != null && !manageUrl.isBlank()) {
            body.append(manageUrl.trim()).append("\n\n");
        }
        // Stated because it is the question a brand asks next, and the answer is reassuring: taking
        // the page back keeps whatever the creator wrote.
        body.append("Taking it back does not lose their work.\n");

        return EmailPort.Message.text(to, page + " has been waiting " + daysWaiting + " days",
                body.toString());
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
