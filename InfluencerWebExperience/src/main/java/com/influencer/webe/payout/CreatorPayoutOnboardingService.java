package com.influencer.webe.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Onboarding a creator onto a payout rail, and reading back whether they can be paid (roadmap
 * PR-47).
 *
 * <p><b>Triggered at INVITATION, not at payout time</b> — the roadmap row is specific about this and
 * the reason is timing rather than tidiness. Stripe's checks take days: identity verification, a
 * bank account, and a tax form each clear separately. Starting at payout time means discovering on
 * the day a brand wants to pay someone that they cannot, which is the one moment the delay is
 * unrecoverable. Starting at invitation means the clock runs while the campaign does.
 *
 * <p><b>The two facts are kept apart everywhere.</b> {@code stripeAccountId} present means onboarding
 * STARTED; {@code payoutsEnabled} means money will actually move. A brand shown only the first would
 * reasonably promise a payout date, and §11.5 records why that is the specific promise not to make.
 *
 * <p><b>Status is stored with the time it was read.</b> A cached boolean with no timestamp is a
 * number nobody can judge: minutes old it is fact, weeks old it is a guess, and the reader cannot
 * tell which without being told.
 */
@Service
public class CreatorPayoutOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(CreatorPayoutOnboardingService.class);

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final CreatorPayoutOnboardingPort onboarding;
    private final String uiBaseUrl;

    public CreatorPayoutOnboardingService(DaoGatewayClient dao,
                                          ResponseShapeService shape,
                                          CreatorPayoutOnboardingPort onboarding,
                                          @Value("${web-experience.ui-base-url}") String uiBaseUrl) {
        this.dao = dao;
        this.shape = shape;
        this.onboarding = onboarding;
        // FIRST value only — the same comma-separated-list trap MemberInvitationService records,
        // where the whole string reached a mail body and was not a link.
        this.uiBaseUrl = uiBaseUrl == null ? ""
                : uiBaseUrl.split(",")[0].trim().replaceAll("/+$", "");
    }

    /**
     * Start or resume onboarding for one creator, returning the URL to send them to.
     *
     * <p>Resume is the common case. A creator who abandoned the flow yesterday gets a fresh link to
     * the SAME account: creating a second would split their payouts across two accounts, and the
     * partial unique index in V52 exists to make that impossible to persist even if this logic
     * were wrong.
     */
    public JsonNode start(UUID brandId, UUID creatorId) {
        if (!onboarding.isConfigured()) {
            // 409 rather than 500: nothing is broken. This deployment pays by hand, and a UI that
            // offered the button anyway is the bug worth reporting.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Payout onboarding is not enabled for this deployment.");
        }
        JsonNode creator = requireOwnedCreator(brandId, creatorId);

        String existing = text(creator, "stripeAccountId");
        CreatorPayoutOnboardingPort.Onboarding result = onboarding.start(
                existing,
                text(creator, "email"),
                // Where the creator lands afterwards. Both are the same page: Stripe sends them to
                // `return` when they finish and `refresh` when the link expired, and that page has
                // to handle both — "finished" is not the same as "succeeded", because Stripe
                // returns them the moment they stop, verified or not.
                uiBaseUrl + "/creators/" + creatorId + "?payout=return",
                uiBaseUrl + "/creators/" + creatorId + "?payout=refresh");

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not start payout onboarding just now. Please try again.");
        }

        // Persisted BEFORE the URL is handed back. If the creator completes onboarding and this
        // write had not happened, the account would exist at Stripe with nothing here pointing at
        // it — and the next attempt would create a second one.
        if (existing == null || !existing.equals(result.accountId())) {
            patch(creatorId, node -> node.put("stripeAccountId", result.accountId()));
        }

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("onboardingUrl", result.onboardingUrl());
        // Said explicitly, because a UI that stores this URL produces a link that is dead by the
        // time anyone clicks it.
        out.put("singleUse", true);
        return out;
    }

    /**
     * Re-read the provider's answer and store it with the time it was read.
     *
     * <p>An unreadable status leaves the stored value ALONE rather than writing false. "We could not
     * ask" and "they cannot be paid" are different facts, and overwriting the second with the first
     * would tell a brand their creator had gone backwards.
     */
    public JsonNode refreshStatus(UUID brandId, UUID creatorId) {
        JsonNode creator = requireOwnedCreator(brandId, creatorId);
        String accountId = text(creator, "stripeAccountId");
        if (accountId == null) {
            ObjectNode out = shape.objectMapper().createObjectNode();
            out.put("payoutsEnabled", false);
            out.put("detail", "Onboarding has not been started.");
            return out;
        }

        CreatorPayoutOnboardingPort.Status status = onboarding.status(accountId);
        if (status == null) {
            log.info("Payout status unknown for creator {}; leaving the stored value alone", creatorId);
            ObjectNode out = shape.objectMapper().createObjectNode();
            out.put("payoutsEnabled", creator.path("payoutsEnabled").asBoolean(false));
            out.put("stale", true);
            return out;
        }

        patch(creatorId, node -> {
            node.put("payoutsEnabled", status.payoutsEnabled());
            node.put("payoutStatusCheckedAt", Instant.now().toString());
        });

        ObjectNode out = shape.objectMapper().createObjectNode();
        out.put("payoutsEnabled", status.payoutsEnabled());
        if (status.detail() != null) {
            out.put("detail", status.detail());
        }
        return out;
    }

    // ---- helpers -------------------------------------------------------

    private JsonNode requireOwnedCreator(UUID brandId, UUID creatorId) {
        JsonNode creator = dao.get("/creators/" + creatorId, new LinkedHashMap<>());
        if (creator == null || !brandId.toString().equals(text(creator, "brandId"))) {
            // Not "forbidden": saying which of the two it was confirms the id exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creator not found");
        }
        return creator;
    }

    private void patch(UUID creatorId, java.util.function.Consumer<ObjectNode> mutation) {
        ObjectNode body = shape.objectMapper().createObjectNode();
        mutation.accept(body);
        dao.patch("/creators/" + creatorId + "/payout-account", body);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }
}
