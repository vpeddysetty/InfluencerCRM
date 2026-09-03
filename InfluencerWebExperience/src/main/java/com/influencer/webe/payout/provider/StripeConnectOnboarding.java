package com.influencer.webe.payout.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.payout.CreatorPayoutOnboardingPort;
import com.influencer.webe.shared.infrastructure.OutboundHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stripe Connect Express onboarding (roadmap PR-47).
 *
 * <p><b>Express, not Standard or Custom, and the choice matters.</b> Express gives Stripe-hosted
 * pages for identity, bank and tax — so a creator's government ID and bank details never touch this
 * application, and W-9/W-8BEN collection is Stripe's obligation rather than one this codebase would
 * have to build and keep current. Custom would mean owning all of that; Standard would require the
 * creator to have their own Stripe account, which most do not.
 *
 * <p><b>Reuses the billing Stripe key.</b> One Stripe account, one secret. A second key for Connect
 * would be a second thing to rotate and a second way for the two halves to end up pointing at
 * different Stripe accounts — which would fail at payout time rather than at configuration time.
 *
 * <p><b>Never throws.</b> A creator sitting in an invitation flow when Stripe has a bad minute
 * should see "we could not start that just now", not a stack trace — and a status read that fails
 * means UNKNOWN, which must never be shown as "not payable". The distinction matters because a
 * brand acting on "not payable" waits, and a brand acting on "unknown" asks.
 */
@Component
@ConditionalOnProperty(name = "web-experience.payout.onboarding.provider", havingValue = "stripe")
public class StripeConnectOnboarding implements CreatorPayoutOnboardingPort {

    private static final Logger log = LoggerFactory.getLogger(StripeConnectOnboarding.class);

    private static final String API = "https://api.stripe.com/v1";

    private final OutboundHttpClient http;
    private final String secretKey;

    public StripeConnectOnboarding(
            OutboundHttpClient http,
            // The SAME key billing uses. See the class note.
            @Value("${web-experience.billing.stripe.secret-key:}") String secretKey) {
        this.http = http;
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    @Override
    public String key() {
        return "stripe";
    }

    @Override
    public boolean isConfigured() {
        return !secretKey.isEmpty();
    }

    @Override
    public Onboarding start(String existingAccountId, String creatorEmail, String returnUrl, String refreshUrl) {
        if (!isConfigured()) {
            return null;
        }
        String accountId = existingAccountId;
        if (accountId == null || accountId.isBlank()) {
            accountId = createAccount(creatorEmail);
            if (accountId == null) {
                return null;
            }
        }
        String url = createAccountLink(accountId, returnUrl, refreshUrl);
        return url == null ? null : new Onboarding(accountId, url);
    }

    /**
     * Create the Connect account.
     *
     * <p>{@code capabilities[transfers]} is requested rather than {@code card_payments}: this
     * platform sends money TO creators and never charges on their behalf, and asking for a
     * capability that is not used lengthens their onboarding with questions that have no purpose.
     */
    private String createAccount(String creatorEmail) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("type", "express");
        if (creatorEmail != null && !creatorEmail.isBlank()) {
            // Prefills the hosted form. Stripe still verifies it; this only saves typing.
            form.put("email", creatorEmail.trim());
        }
        form.put("capabilities[transfers][requested]", "true");

        OutboundHttpClient.Response response = http.postForm(API + "/accounts", form, authHeaders());
        if (!response.ok()) {
            // The status, not the body: a Stripe error body can echo the email, and this line goes
            // to a log that is not a secret store.
            log.warn("Stripe Connect account creation failed with status {}", response.status());
            return null;
        }
        return text(response.body(), "id");
    }

    /**
     * The single-use link the creator follows.
     *
     * <p>{@code account_onboarding} rather than {@code account_update}: this is the first run
     * through, and the update flow assumes an already-complete account.
     *
     * <p>The link expires in minutes and cannot be reissued by reloading, which is why
     * {@code refreshUrl} exists — Stripe sends the creator there when it has expired, and that
     * endpoint's whole job is to mint a new one. Storing or emailing this URL would produce a link
     * that is dead by the time anyone clicks it.
     */
    private String createAccountLink(String accountId, String returnUrl, String refreshUrl) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("account", accountId);
        form.put("type", "account_onboarding");
        form.put("return_url", returnUrl);
        form.put("refresh_url", refreshUrl);

        OutboundHttpClient.Response response = http.postForm(API + "/account_links", form, authHeaders());
        if (!response.ok()) {
            log.warn("Stripe Connect account link failed with status {}", response.status());
            return null;
        }
        return text(response.body(), "url");
    }

    @Override
    public Status status(String accountId) {
        if (!isConfigured() || accountId == null || accountId.isBlank()) {
            return null;
        }
        OutboundHttpClient.Response response = http.postForm(
                API + "/accounts/" + accountId, Map.of(), authHeaders());
        if (!response.ok()) {
            // UNKNOWN, not "not payable". A brand acting on the former asks; on the latter, waits.
            log.info("Stripe Connect status read failed with status {}", response.status());
            return null;
        }
        JsonNode body = response.body();
        boolean enabled = body != null && body.path("payouts_enabled").asBoolean(false);
        return new Status(accountId, enabled, outstanding(body));
    }

    /**
     * What Stripe is still waiting for, in words a brand can act on.
     *
     * <p>Read from {@code requirements.currently_due} rather than inferred: guessing why an account
     * is not payable produces a confident wrong answer, and the commonest cause — a tax form — is
     * exactly the one somebody needs to chase.
     */
    private String outstanding(JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode due = body.path("requirements").path("currently_due");
        if (!due.isArray() || due.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : due) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(item.asText());
            if (sb.length() > 300) {
                break;
            }
        }
        return sb.toString();
    }

    private Map<String, String> authHeaders() {
        return Map.of("Authorization", "Bearer " + secretKey);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }
}
