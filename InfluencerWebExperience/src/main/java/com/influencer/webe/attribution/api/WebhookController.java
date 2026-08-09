package com.influencer.webe.attribution.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.attribution.application.AttributionService;
import com.influencer.webe.attribution.application.MarketplaceService;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Order ingestion endpoints (Phase 3).
 *
 * <h2>Why this endpoint had to change</h2>
 *
 * <p>It previously took {@code brandId} from the caller's query string or body and never called
 * {@link com.influencer.webe.marketplace.MarketplaceProvider#verifyWebhook}, which has been on the
 * SPI the whole time. So anyone who knew the URL could post a fabricated order naming any brand and
 * any coupon code, and it flowed into attribution — which accrues commission, which becomes a
 * payout. A path from an anonymous HTTP request to money owed.
 *
 * <p>It was survivable only because the mock is the sole provider and the URL is not published.
 * Connecting a real store makes the endpoint publicly reachable by necessity, which is why this is
 * fixed before the Shopify adapter rather than as part of it.
 *
 * <h2>The two changes</h2>
 *
 * <p><b>The brand comes from the store, never from the request.</b> The connection row is looked up
 * by the provider's own store identifier, and its {@code brandId} is used. A caller cannot name a
 * brand it does not own because it no longer names one at all.
 *
 * <p><b>The signature is verified against the raw body.</b> Taken as a {@code String}, not a parsed
 * {@code JsonNode}: an HMAC covers the exact bytes sent, and a body parsed and re-serialised
 * differs in key order and spacing and will never verify. {@code StripeSignature} learned this
 * already and this follows it.
 */
@RestController
@RequestMapping("/api")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final AttributionService attributionService;
    private final RequestUserResolver requestUserResolver;
    private final MarketplaceService marketplaceService;
    private final ObjectMapper objectMapper;

    public WebhookController(AttributionService attributionService,
                             RequestUserResolver requestUserResolver,
                             MarketplaceService marketplaceService,
                             ObjectMapper objectMapper) {
        this.attributionService = attributionService;
        this.requestUserResolver = requestUserResolver;
        this.marketplaceService = marketplaceService;
        this.objectMapper = objectMapper;
    }

    /**
     * The public webhook a marketplace calls.
     *
     * <p>Unauthenticated by nature — a store holds no user token — so the provider's signature
     * <em>is</em> the authentication, exactly as it is for the billing webhook.
     *
     * @param rawBody the exact bytes as received; required for the HMAC to verify
     */
    @PostMapping("/webhooks/marketplace/{providerKey}")
    @ResponseStatus(HttpStatus.OK)
    public JsonNode webhook(@PathVariable String providerKey,
                            @RequestHeader Map<String, String> headers,
                            @RequestBody String rawBody) {

        // Resolve the store first: this both authenticates the caller (a signature is checked
        // against that connection's credentials) and supplies the tenant. Doing it in one step is
        // deliberate — a brand resolved separately from the signature check could be a brand the
        // signature does not actually cover.
        MarketplaceService.VerifiedWebhook verified =
                marketplaceService.verifyWebhook(providerKey, headers, rawBody);

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception malformed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed webhook payload");
        }

        log.info("Accepted a verified {} webhook for brand {}", providerKey, verified.brandId());
        return attributionService.ingest(verified.brandId(), providerKey, payload);
    }

    /**
     * Auth-scoped simulation of an order, for testing and demos.
     *
     * <p>Unchanged and still safe: the brand comes from the caller's verified token via
     * {@code resolveBrandId}, which rejects a brand the token cannot reach. This is the path the
     * e2e journeys use, and it is why the webhook above did not need to stay permissive for tests.
     */
    @PostMapping("/attribution/simulate")
    public JsonNode simulate(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody ObjectNode payload) {
        UUID brandId = requestUserResolver.resolveBrandId(authorization, getUuid(payload, "brandId"));
        String providerKey = payload.hasNonNull("providerKey") ? payload.get("providerKey").asText() : "mock";
        JsonNode order = payload.get("order");
        if (order == null || !order.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "order object is required");
        }
        return attributionService.ingest(brandId, providerKey, order);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
