#!/usr/bin/env bash
# LOCAL ONLY, and unlike its siblings it takes no guard (roadmap OP-33).
#
# This is a report, not a test: it prints rows out of the local Postgres container and asserts
# nothing. There is no API target to point elsewhere, so there is no way to aim it at a deployment
# by mistake -- which is the failure the other suites needed guarding against. It reads whatever
# database is on this machine, and says so here so the next reader does not go looking for a
# missing guard.
SP="${E2E_WORKDIR:-$(dirname "$0")}"; . "$SP/ids.env"
q() { docker exec influencercrm-postgres psql -U influencercrm_user -d influencercrm_db -c "$1"; }

echo "==================== J-A: AGENCY OWNER (Northstar) ===================="
echo "--- A.1 identity: user -> account -> brands -> membership ---"
q "SELECT u.email, a.name AS account, a.account_type, m.role, b.name AS brand
   FROM identity.users u
   JOIN identity.memberships m ON m.user_id=u.id
   JOIN identity.accounts a    ON a.id=m.account_id
   JOIN identity.brands b      ON b.account_id=a.id
   WHERE u.id='$A_UID' ORDER BY b.name;"

echo "--- A.2 creator written to Aurora, with tenancy + audit columns ---"
q "SELECT c.handle, c.name, c.platform, c.follower_count, c.preferred_rate, c.currency,
          b.name AS brand, c.created_by_user_id=u.id AS audit_user_ok, c.source
   FROM creator.creators c
   JOIN identity.brands b ON b.id=c.brand_id
   JOIN identity.users u  ON u.id='$A_UID'
   WHERE c.id='$A_CREATOR';"

echo "--- A.3 SAME HANDLE, TWO BRANDS: per-brand rows with independent rates ---"
q "SELECT b.name AS brand, c.handle, c.preferred_rate, c.id
   FROM creator.creators c JOIN identity.brands b ON b.id=c.brand_id
   WHERE c.handle='@shared_star' ORDER BY b.name;"

echo "--- A.4 campaign + assignment + coupon chain (all in Aurora) ---"
q "SELECT ca.name AS campaign, ca.budget, ca.status, ca.campaign_type,
          cc.outreach_status, cc.contract_status, cc.agreed_fee,
          co.code, co.discount_type, co.discount_value, co.is_active
   FROM campaign.campaigns ca
   LEFT JOIN creator.campaign_creators cc ON cc.campaign_id=ca.id
   LEFT JOIN attribution.influencer_campaign_codes co ON co.campaign_id=ca.id
   WHERE ca.id='$A_CAMP';"

echo "--- A.5 every Aurora row carries the same brand_id (tenancy stamp) ---"
q "SELECT 'creator' AS entity, brand_id::text FROM creator.creators WHERE id='$A_CREATOR'
   UNION ALL SELECT 'campaign', brand_id::text FROM campaign.campaigns WHERE id='$A_CAMP'
   UNION ALL SELECT 'assignment', brand_id::text FROM creator.campaign_creators WHERE id='$A_CC'
   UNION ALL SELECT 'coupon', brand_id::text FROM attribution.influencer_campaign_codes WHERE id='$A_CODE'
   UNION ALL SELECT 'board', brand_id::text FROM workflow.workflow_boards WHERE id='$A_BOARD'
   UNION ALL SELECT 'card', brand_id::text FROM workflow.workflow_cards WHERE id='$A_CARD';"

echo "--- A.6 workflow board, its 7 stages, and the card's current stage ---"
q "SELECT b.name AS board, b.is_active, count(s.id) AS stages
   FROM workflow.workflow_boards b LEFT JOIN workflow.workflow_board_stages s ON s.board_id=b.id
   WHERE b.id='$A_BOARD' GROUP BY b.id,b.name,b.is_active;"
q "SELECT position, stage_name FROM workflow.workflow_board_stages WHERE board_id='$A_BOARD' ORDER BY position;"
q "SELECT c.name AS card, s.stage_name AS now_in_stage, c.position, c.agreed_fee, c.fee_currency, c.status
   FROM workflow.workflow_cards c LEFT JOIN workflow.workflow_board_stages s ON s.id=c.stage_id
   WHERE c.id='$A_CARD';"

echo "==================== J-B: SOLO BRAND OWNER (Veridian) ===================="
echo "--- B.1 signup auto-provisioned account + brand + OWNER membership ---"
q "SELECT u.email, u.brand_name, a.name AS account, a.account_type, m.role, m.status, b.name AS brand
   FROM identity.users u
   JOIN identity.memberships m ON m.user_id=u.id
   JOIN identity.accounts a    ON a.id=m.account_id
   JOIN identity.brands b      ON b.account_id=a.id
   WHERE u.id='$B_UID';"

echo "--- B.2 creator + campaign + assignment + coupon ---"
q "SELECT c.handle, c.platform, c.follower_count, c.preferred_rate FROM creator.creators c WHERE c.id='$B_CREATOR';"
q "SELECT ca.name AS campaign, ca.budget, ca.campaign_type, ca.status,
          cc.agreed_fee, cc.outreach_status, cc.contract_status
   FROM campaign.campaigns ca LEFT JOIN creator.campaign_creators cc ON cc.campaign_id=ca.id
   WHERE ca.id='$B_CAMP';"
q "SELECT code, discount_type, discount_value, commission_type, commission_value, is_active, sync_status
   FROM attribution.influencer_campaign_codes WHERE id='$B_CODE';"

echo "--- B.3 MONEY CHAIN: order -> attribution -> commission -> payout ---"
q "SELECT sa.order_id, sa.sale_amount, sa.discount_amount, sa.currency, sa.status AS attribution_status,
          co.code AS via_coupon
   FROM attribution.influencer_sale_attributions sa
   LEFT JOIN attribution.influencer_campaign_codes co ON co.id=sa.campaign_code_id
   WHERE sa.brand_id='$B_BRAND' ORDER BY sa.created_at DESC LIMIT 5;"
q "SELECT id, gross_sale, commission_amount, status, currency, payout_id
   FROM finance.influencer_commissions WHERE brand_id='$B_BRAND' ORDER BY created_at DESC LIMIT 5;"
q "SELECT id, total_amount, currency, status, provider_key, creator_id
   FROM finance.influencer_payouts WHERE brand_id='$B_BRAND' ORDER BY created_at DESC LIMIT 5;"

echo "--- B.4 commission arithmetic: 420.00 sale x 12% = 50.40 ---"
q "SELECT sa.sale_amount, co.commission_type, co.commission_value, c.commission_amount,
          ROUND(sa.sale_amount * co.commission_value/100.0, 2) AS expected,
          (c.commission_amount = ROUND(sa.sale_amount * co.commission_value/100.0,2)) AS matches
   FROM finance.influencer_commissions c
   JOIN attribution.influencer_sale_attributions sa ON sa.id=c.attribution_id
   JOIN attribution.influencer_campaign_codes co ON co.id=sa.campaign_code_id
   WHERE c.brand_id='$B_BRAND' ORDER BY c.created_at DESC LIMIT 3;"

echo "--- B.5 brand's workflow board + stages + card ---"
q "SELECT b.name AS board, count(s.id) AS stages FROM workflow.workflow_boards b
   LEFT JOIN workflow.workflow_board_stages s ON s.board_id=b.id
   WHERE b.id='$B_BOARD' GROUP BY b.id,b.name;"
q "SELECT c.name AS card, s.stage_name, c.agreed_fee, c.status
   FROM workflow.workflow_cards c LEFT JOIN workflow.workflow_board_stages s ON s.id=c.stage_id
   WHERE c.id='$B_CARD';"

echo "--- B.6 campaign brief (content context) ---"
q "SELECT status, content::text AS content FROM campaign.campaign_briefs
   WHERE brand_id='$B_BRAND' ORDER BY created_at DESC LIMIT 3;"

echo "==================== J-C: TENANCY PROOF ===================="
echo "--- C.1 zero rows leak across the two tenants ---"
q "SELECT 'agency rows visible under solo brand' AS check_name,
          count(*) AS must_be_zero
   FROM creator.creators WHERE brand_id='$B_BRAND' AND id='$A_CREATOR';"
q "SELECT 'solo rows visible under agency brand' AS check_name,
          count(*) AS must_be_zero
   FROM campaign.campaigns WHERE brand_id IN ('$A_BRAND1','$A_BRAND2') AND id='$B_CAMP';"

echo "--- C.2 no row anywhere is missing its tenancy key ---"
q "SELECT 'creators' t, count(*) AS null_brand_id FROM creator.creators WHERE brand_id IS NULL
   UNION ALL SELECT 'campaigns', count(*) FROM campaign.campaigns WHERE brand_id IS NULL
   UNION ALL SELECT 'campaign_creators', count(*) FROM creator.campaign_creators WHERE brand_id IS NULL
   UNION ALL SELECT 'coupons', count(*) FROM attribution.influencer_campaign_codes WHERE brand_id IS NULL
   UNION ALL SELECT 'commissions', count(*) FROM finance.influencer_commissions WHERE brand_id IS NULL
   UNION ALL SELECT 'payouts', count(*) FROM finance.influencer_payouts WHERE brand_id IS NULL
   UNION ALL SELECT 'workflow_boards', count(*) FROM workflow.workflow_boards WHERE brand_id IS NULL
   UNION ALL SELECT 'workflow_cards', count(*) FROM workflow.workflow_cards WHERE brand_id IS NULL;"

echo "--- C.3 the MARKETER's write landed in Aurora, under their own identity ---"
q "SELECT c.handle, b.name AS brand, u.email AS created_by
   FROM creator.creators c
   JOIN identity.brands b ON b.id=c.brand_id
   LEFT JOIN identity.users u ON u.id=c.created_by_user_id
   WHERE c.id='$M_CREATOR';"

echo "--- C.4 sessions are server-side in Redis, nothing in the browser ---"
docker exec influencercrm-redis redis-cli --scan --pattern 'dps:session:*' | head -5
echo -n "redis session keys: "; docker exec influencercrm-redis redis-cli --scan --pattern 'dps:session:*' | wc -l
