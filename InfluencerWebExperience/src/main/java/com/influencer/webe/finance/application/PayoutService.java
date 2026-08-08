package com.influencer.webe.finance.application;

import com.influencer.webe.shared.application.ResponseShapeService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import com.influencer.webe.payout.PayoutProvider;
import com.influencer.webe.payout.PayoutProviderRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Influencer payout workflow (Phase 5). Commissions accrue as {@code pending},
 * are {@code approved} after a hold window, then batched into an
 * {@code influencer_payouts} row and settled via a {@link PayoutProvider}. The
 * ledger is treated append-only in spirit: paid commissions are never mutated
 * back.
 */
@Service
public class PayoutService {
    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final PayoutProviderRegistry registry;

    public PayoutService(DaoGatewayClient dao,
                         ResponseShapeService shape,
                         PayoutProviderRegistry registry) {
        this.dao = dao;
        this.shape = shape;
        this.registry = registry;
    }

    /** Provider catalog for the UI. */
    public JsonNode listProviders() {
        ArrayNode out = shape.objectMapper().createArrayNode();
        for (PayoutProvider p : registry.all()) {
            ObjectNode node = out.addObject();
            node.put("key", p.key());
            node.put("displayName", p.displayName());
        }
        return out;
    }

    /** Move a commission pending → approved (the payout eligibility gate). */
    public JsonNode approveCommission(UUID brandId, UUID commissionId) {
        JsonNode commission = dao.get("/influencer-commissions/" + commissionId, null);
        requireOwner(commission, brandId, "commission");
        String status = text(commission, "status");
        if (!"pending".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only pending commissions can be approved (was " + status + ")");
        }
        ObjectNode update = commission.deepCopy();
        update.put("status", "approved");
        update.put("approvedAt", Instant.now().toString());
        return shape.commission(dao.put("/influencer-commissions/" + commissionId, update));
    }

    /**
     * Create a payout batch settling all {@code approved} commissions for a creator.
     * Sums them, creates the payout, executes it through the provider, then marks
     * the commissions {@code paid} and links them to the payout.
     */
    public JsonNode createPayout(UUID brandId, UUID creatorId, String providerKey) {
        PayoutProvider provider = registry.find(providerKey == null ? "manual" : providerKey).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payout provider: " + providerKey));

        // Collect this creator's approved commissions.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        q.put("status", "approved");
        JsonNode commissions = dao.get("/influencer-commissions", q);

        BigDecimal total = BigDecimal.ZERO;
        String currency = "USD";
        ArrayNode toSettle = shape.objectMapper().createArrayNode();
        if (commissions != null && commissions.isArray()) {
            for (JsonNode c : commissions) {
                if (c.hasNonNull("creatorId") && c.get("creatorId").asText().equals(creatorId.toString())) {
                    total = total.add(decimal(c, "commissionAmount"));
                    if (c.hasNonNull("currency")) {
                        currency = c.get("currency").asText();
                    }
                    toSettle.add(c);
                }
            }
        }

        if (toSettle.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No approved commissions to pay for this creator");
        }

        // Create the payout row (draft).
        ObjectNode payoutPayload = shape.objectMapper().createObjectNode();
        payoutPayload.put("brandId", brandId.toString());
        payoutPayload.put("creatorId", creatorId.toString());
        payoutPayload.put("totalAmount", total.toPlainString());
        payoutPayload.put("currency", currency);
        payoutPayload.put("method", provider.key());
        payoutPayload.put("providerKey", provider.key());
        payoutPayload.put("status", "processing");
        JsonNode payout = dao.post("/influencer-payouts", payoutPayload);
        String payoutId = payout.get("id").asText();

        // Execute payment. The payout row is created first precisely so its id can serve as the
        // idempotency key: if this call times out and is retried, the provider sees the same key
        // and settles once. A key generated here instead would differ on the retry and pay twice.
        PayoutProvider.PayoutResult result;
        try {
            result = provider.pay(payoutId, creatorId.toString(), total, currency,
                    "Influencer commission payout");
        } catch (RuntimeException e) {
            // A throw would otherwise leave the row stranded in "processing" forever, with the
            // commissions still "approved" — invisible to both the payouts list and the next
            // payout attempt. Record the failure against the row, then report it.
            ObjectNode failed = payout.deepCopy();
            failed.put("status", "failed");
            try {
                dao.put("/influencer-payouts/" + payoutId, failed);
            } catch (RuntimeException ignored) {
                // Nothing further to do; the original failure is the one worth reporting.
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Payout failed: " + e.getMessage());
        }

        ObjectNode payoutUpdate = payout.deepCopy();
        payoutUpdate.put("providerRef", result.getProviderRef());
        payoutUpdate.put("status", result.getStatus());
        if ("paid".equals(result.getStatus())) {
            payoutUpdate.put("paidAt", Instant.now().toString());
        }
        JsonNode finalPayout = dao.put("/influencer-payouts/" + payoutId, payoutUpdate);

        // Link + flip commissions to paid only if payment succeeded.
        if (result.isSuccess()) {
            for (JsonNode c : toSettle) {
                ObjectNode cu = c.deepCopy();
                cu.put("status", "paid");
                cu.put("payoutId", payoutId);
                dao.put("/influencer-commissions/" + c.get("id").asText(), cu);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Payout failed: " + result.getMessage());
        }

        return shape.payout(finalPayout);
    }

    // ---- helpers -------------------------------------------------------

    private void requireOwner(JsonNode row, UUID brandId, String label) {
        if (row == null || row.isNull() || !row.hasNonNull("id")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + " not found");
        }
        if (!row.hasNonNull("brandId") || !row.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your " + label);
        }
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.get(field).asText());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
