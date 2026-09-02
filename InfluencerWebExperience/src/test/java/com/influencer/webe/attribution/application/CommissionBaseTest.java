package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a percentage commission is a percentage OF (roadmap OP-21).
 *
 * <p><b>The bug this pins.</b> Commission was computed from the GROSS sale while {@code netAmount}
 * was stored as {@code sale - discount} on the very same attribution row. A 20%-off order therefore
 * paid the creator a share of money the ledger's own net figure said the brand never received. No
 * one had complained because there are no paying brands yet — and the first dispute would have been
 * unanswerable, because the product asserted both numbers at once.
 *
 * <p>The base is now: <b>net revenue after discount, excluding tax and shipping.</b> The same
 * sentence appears in `CouponsPage.jsx` beside the field where the rate is set. These tests are what
 * stop the two drifting back apart.
 */
class CommissionBaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BigDecimal commission(String type, String value, String sale, String discount) throws Exception {
        ObjectNode coupon = MAPPER.createObjectNode();
        coupon.put("commissionType", type);
        coupon.put("commissionValue", value);

        Method m = AttributionService.class.getDeclaredMethod(
                "computeCommission", JsonNode.class, BigDecimal.class, BigDecimal.class);
        m.setAccessible(true);
        // The method touches no field, so a null-constructed instance is enough and keeps this a
        // unit test rather than a wiring exercise.
        AttributionService service = newService();
        return (BigDecimal) m.invoke(service, coupon, new BigDecimal(sale), new BigDecimal(discount));
    }

    private AttributionService newService() throws Exception {
        var ctor = AttributionService.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object[] args = new Object[ctor.getParameterCount()];
        return (AttributionService) ctor.newInstance(args);
    }

    @Test
    @DisplayName("a percentage is taken on net revenue, not on the gross sale")
    void percentageUsesNet() throws Exception {
        // £100 order, £20 off, 10% commission. Net is £80, so £8 — not the £10 the old gross
        // calculation paid, which is a share of money the brand never received.
        assertThat(commission("percent", "10", "100.00", "20.00"))
                .isEqualByComparingTo(new BigDecimal("8.00"));
    }

    @Test
    @DisplayName("with no discount, net and gross agree — so nothing changes for a full-price order")
    void noDiscountIsUnchanged() throws Exception {
        assertThat(commission("percent", "10", "100.00", "0.00"))
                .isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    @DisplayName("a fully comped order pays nothing rather than a negative commission")
    void discountLargerThanSale() throws Exception {
        // A discount exceeding the sale is data, not an impossibility — a 100%-off code, or a
        // marketplace reporting them separately. Paying a negative commission would silently
        // reduce a creator's balance for making a sale.
        assertThat(commission("percent", "10", "50.00", "80.00"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a fixed commission is per order and ignores both sale and discount")
    void fixedIsUnaffected() throws Exception {
        assertThat(commission("fixed", "5", "100.00", "40.00"))
                .isEqualByComparingTo(new BigDecimal("5.00"));
    }

    @Test
    @DisplayName("rounding is half-up to the penny, because a fraction of a penny is not payable")
    void roundsToPennies() throws Exception {
        // Net 33.33 at 33% is 10.9989 — one penny either way is a real difference to whoever is
        // owed it, so the rule is stated rather than left to the default.
        assertThat(commission("percent", "33", "33.33", "0.00"))
                .isEqualByComparingTo(new BigDecimal("11.00"));
    }

    @Test
    @DisplayName("an unknown commission type pays nothing rather than guessing")
    void unknownTypePaysNothing() throws Exception {
        assertThat(commission("tiered", "10", "100.00", "0.00"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
