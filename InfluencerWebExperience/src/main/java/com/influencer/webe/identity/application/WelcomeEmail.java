package com.influencer.webe.identity.application;

import com.influencer.webe.shared.application.EmailPort;

/**
 * Composes the email a new workspace gets on signup (roadmap PR-02).
 *
 * <p><b>Why it exists.</b> Nothing greeted a new signup — `PRODUCT-GAPS.md` said so and it stayed
 * true. The in-product checklist now answers "what do I do first" for someone who is already
 * looking at the screen; this answers it for the far more common case, which is someone who signed
 * up, got interrupted, and closed the tab. An empty workspace and no reason to return is how a free
 * trial ends without anyone deciding anything.
 *
 * <p><b>It says the same five things the checklist says, in the same order.</b> Deliberately: two
 * different answers to "what first" is worse than one, and the order is the argued one — creator
 * before store, because the store depends on someone else's system. If `shell/activation.js`
 * changes, this changes with it. That coupling is the point, and `WelcomeEmailTest` pins it.
 *
 * <p><b>Text-only, like every other message here.</b> The body carries a brand name the user typed,
 * and an HTML body would make that an injection surface for no gain a plain link does not already
 * provide — the same reasoning {@link InvitationEmail} records.
 *
 * <p><b>What it deliberately does NOT do:</b> no unsubscribe link, because this is a single
 * transactional message tied to an account someone just created rather than a list they can leave;
 * and no discount, deadline or "your trial expires" pressure, because the free tier has no time
 * limit and saying otherwise would be a lie the pricing page contradicts.
 */
public final class WelcomeEmail {

    private WelcomeEmail() {
    }

    /**
     * Builds the welcome message.
     *
     * @param to         the new account holder's address
     * @param brandName  the workspace they just named, shown back so the mail is obviously theirs
     * @param appUrl     where to sign in — the deployed UI origin, never hardcoded
     */
    public static EmailPort.Message compose(String to, String brandName, String appUrl) {
        String workspace = brandName == null || brandName.isBlank() ? "your workspace" : brandName.trim();

        String body = """
                Welcome to Tejdux.

                %s is ready. Here is the shortest path to seeing a sale credited to a creator:

                1. Add your first creator — everything else hangs off one.
                2. Create a campaign — what a code and a page belong to.
                3. Give that creator a discount code — this is what credits a sale to them.
                4. Publish a landing page — the page they share, personalised per code.
                5. Connect your store — orders then land against the codes automatically.

                The same list is waiting on your board, and it ticks itself off as you go.

                %s

                You are on the free plan: one brand, 25 creators, no card and no time limit.

                — Tejdux
                """.formatted(workspace, appUrl == null || appUrl.isBlank() ? "" : appUrl).strip();

        // The workspace name in the subject is what makes this recognisable in a crowded inbox
        // three days later, which is exactly when it has to work.
        return EmailPort.Message.text(to, "Welcome to Tejdux — getting " + workspace + " to its first sale", body);
    }
}
