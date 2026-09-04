package com.influencer.webe.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.identity.application.PlanPolicy;
import com.influencer.webe.identity.application.PlatformOwnerPolicy;
import com.influencer.webe.identity.application.SubscriptionService;
import com.influencer.webe.security.Permission;
import com.influencer.webe.security.TenantContext;
import com.influencer.webe.shared.application.RequestUserResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Subscribing, pausing, resuming and cancelling (roadmap M2.1/M2.2).
 *
 * <h2>Who may do what</h2>
 * <ul>
 *   <li><b>Viewing</b> needs {@link Permission#ACCOUNT_BILLING_READ} — OWNER and ADMIN. An admin
 *       administers the account and needs to know what it is on and what it has paid.</li>
 *   <li><b>Changing</b> needs {@link Permission#ACCOUNT_BILLING} — OWNER only. Pausing or
 *       cancelling stops the company's service, and an invited admin should not be able to do that
 *       to the person who owns the account.</li>
 * </ul>
 *
 * <p>That split is deliberate and was the existing decision before this feature: {@code
 * ACCOUNT_BILLING} was already OWNER-only, with a test asserting ADMIN does not hold it. The read
 * permission is new; the write boundary is unchanged.
 *
 * <p>The account is always taken from the verified token, never from a path or body. An accountId
 * a caller could supply would let anyone with billing rights on their own account cancel someone
 * else's subscription.
 */
@RestController
@RequestMapping("/api/billing")
public class SubscriptionController {

    // EVERY route here is gated to the platform owner while pricing is undecided (2026-09).
    //
    // Not deleted, not disabled, not behind a feature flag that silently changes shape: the billing
    // path stays exactly as built and tested, and one policy decides who reaches it. Offering a
    // customer a checkout for a product whose price has not been decided is the thing to avoid --
    // the code is fine, the offer is what does not exist yet.
    //
    // The refusal is 404, not 403. A 403 confirms there is a billing system here and says "not
    // you", which invites the question this is meant to avoid.

    private final SubscriptionService subscriptions;
    private final RequestUserResolver requestUserResolver;
    private final PlatformOwnerPolicy platformOwner;

    public SubscriptionController(SubscriptionService subscriptions,
                                  RequestUserResolver requestUserResolver,
                                  PlatformOwnerPolicy platformOwner) {
        this.subscriptions = subscriptions;
        this.requestUserResolver = requestUserResolver;
        this.platformOwner = platformOwner;
    }

    /** The current subscription, or {@code subscribed: false} when there is none. */
    @GetMapping("/subscription")
    public SubscriptionResponse current(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING_READ);
        platformOwner.requirePlatformOwner(context);

        JsonNode subscription = subscriptions.current(context.accountId());
        // Whether this caller may act, sent alongside the state so the UI does not have to
        // re-derive the permission rule and risk disagreeing with the server.
        boolean canManage = context.hasPermission(Permission.ACCOUNT_BILLING);
        return new SubscriptionResponse(subscription != null, subscription, canManage);
    }

    @GetMapping("/invoices")
    public JsonNode invoices(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING_READ);
        platformOwner.requirePlatformOwner(context);
        return subscriptions.invoices(context.accountId());
    }

    @PostMapping("/subscribe")
    public JsonNode subscribe(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @Valid @RequestBody SubscribeRequest request) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING);
        platformOwner.requirePlatformOwner(context);
        return subscriptions.subscribe(context.accountId(), request.plan(),
                request.billingInterval(), request.successUrl(), request.cancelUrl());
    }

    @PostMapping("/pause")
    public JsonNode pause(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING);
        platformOwner.requirePlatformOwner(context);
        return subscriptions.pause(context.accountId());
    }

    @PostMapping("/resume")
    public JsonNode resume(@RequestHeader(value = "Authorization", required = false) String authorization) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING);
        platformOwner.requirePlatformOwner(context);
        return subscriptions.resume(context.accountId());
    }

    /**
     * Cancels — at the end of the paid period by default.
     *
     * <p>{@code immediate} is opt-in rather than the default: someone who cancels on day 2 of a
     * month has paid for the other 28 and should keep them. Ending access at once would be taking
     * back something already bought.
     */
    @PostMapping("/cancel")
    public JsonNode cancel(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody(required = false) CancelRequest request) {
        TenantContext context =
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING);
        platformOwner.requirePlatformOwner(context);
        return subscriptions.cancel(context.accountId(), request != null && request.immediate());
    }

    /** The plans on offer, for a signed-in upgrade screen. */
    @GetMapping("/plans")
    public java.util.List<PlanOption> plans(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        platformOwner.requirePlatformOwner(
                requestUserResolver.requirePermission(authorization, Permission.ACCOUNT_BILLING_READ));
        return java.util.Arrays.stream(PlanPolicy.values())
                .map(plan -> new PlanOption(
                        plan.key(),
                        plan.limitFor(PlanPolicy.Resource.BRAND),
                        plan.limitFor(PlanPolicy.Resource.CREATOR),
                        plan.limitFor(PlanPolicy.Resource.MEMBER),
                        plan.limitFor(PlanPolicy.Resource.LANDING_PAGE)))
                .toList();
    }

    /**
     * @param subscribed false when the account has never subscribed — a normal state, not an error
     * @param canManage  whether THIS caller may pause or cancel. An admin sees the subscription
     *                   with {@code canManage=false}
     */
    public record SubscriptionResponse(boolean subscribed, JsonNode subscription, boolean canManage) {
    }

    /**
     * @param billingInterval {@code "monthly"} or {@code "yearly"}. Optional: an absent or
     *                        unrecognised value bills monthly, because that is the smaller
     *                        commitment and a client should never be charged for a year by
     *                        omitting a field
     */
    public record SubscribeRequest(@NotBlank String plan, String billingInterval,
                                   String successUrl, String cancelUrl) {
    }

    public record CancelRequest(boolean immediate) {
    }

    /**
     * Limits only — no price.
     *
     * <p>No price has been decided, and the server should not invent one. When billing is real the
     * price comes from the provider's own catalogue, which is the only place it can be right.
     */
    public record PlanOption(String plan, int brands, int creators, int members, int landingPages) {
    }
}
