# Database Report — Per-Journey Verification

**Date:** 2026-08-02
**Companion to:** [E2E-TEST-REPORT-2026-08-02.md](E2E-TEST-REPORT-2026-08-02.md)
**Database:** `influencercrm_db` on Postgres 15 (pgvector), 26 tables across 9 schemas

Every row below was produced by driving the running application through the Digital Presentation
Service — the same path a browser takes — and then read back with `psql`. Nothing here was inserted
directly.

---

## 1. Entity ids from this run

```
A_UID=28fcf9ff-aad8-49b2-aaf1-da78cc1618d3
A_ACCT=dededede-0000-0000-0000-0000000000aa
A_BRAND1=dededede-0000-0000-0000-0000000000b1
A_BRAND2=dededede-0000-0000-0000-0000000000b2
A_CREATOR=2db37c7a-0076-4b52-8200-3a17e9ebd537
L_CREATOR=211ec8e1-6da0-4925-b590-9cc13a0d3761
A_CAMP=46fb5c77-548c-40fd-93ad-78acc0fd11f4
A_CC=9006a8ad-fceb-4988-9580-3aeb404747a5
A_CODE=105a88a2-3000-40ef-b882-6f5004545e1e
A_BOARD=527f20a2-6f8b-491e-a6e6-6c9674df14be
A_CARD=f5e900cd-a546-400a-a8de-5ca70e3a2f50
M_CREATOR=e4bedb61-14b8-49b3-85f1-8335a9ef9bd3
B_UID=bab5f3cf-2339-4269-8835-dca39b4e1100
B_BRAND=ee70e0a6-06d5-458a-b929-5b651cd5fb35
B_CREATOR=6361eaf6-ccdf-4212-94c4-7591c6e910a6
B_CAMP=f9563a8a-e0dd-4920-bd35-466e553180a2
B_CODE=7b9909cb-ec8a-4011-a76f-636433922dcb
B_COMM=ccc763b9-6414-410e-bb72-29b7135512df
B_BOARD=a516bb3c-5d9b-4b56-9633-a99561559913
B_CARD=f57fe96b-a788-45a1-a32a-544e087d4f01
```

`A_*` = agency journey (Northstar / Aurora), `L_*` = the Lumen-side creator,
`B_*` = solo brand journey (Veridian), `M_*` = the MARKETER's write.

---

## 2. What each section proves

| Section | Question it answers |
|---|---|
| A.1 | Does an agency user resolve to one account with two brands? |
| A.2 | Is the creator stamped with the right brand **and** the acting user? |
| A.3 | Do two brands hold independent rows for the same handle? |
| A.4 | Did the campaign → assignment → coupon chain persist together? |
| A.5 | Does every row in the journey carry the same tenancy key? |
| A.6 | Did the board, its 7 stages, and the card placement persist? |
| B.1 | Did one signup provision account + brand + OWNER membership? |
| B.2 | Did the solo brand's CRM records persist? |
| B.3 | Did the money chain run end to end? |
| B.4 | Is the commission arithmetically correct? |
| B.5–B.6 | Did workflow and content records persist? |
| C.1–C.2 | Does any row leak across tenants, or lack a tenancy key? |
| C.3 | Was the MARKETER's write attributed to the MARKETER? |
| C.4 | Do sessions live server-side rather than in the browser? |

---

## 3. Raw verification output

```text
==================== J-A: AGENCY OWNER (Northstar) ====================
--- A.1 identity: user -> account -> brands -> membership ---
           email           |         account         | account_type | role  |         brand          
---------------------------+-------------------------+--------------+-------+------------------------
 demo.admin@northstar.test | Northstar Agency (demo) | agency       | ADMIN | Aurora Beauty (client)
 demo.admin@northstar.test | Northstar Agency (demo) | agency       | ADMIN | Lumen Fitness (client)
(2 rows)

--- A.2 creator written to Aurora, with tenancy + audit columns ---
    handle    |    name     | platform  | follower_count | preferred_rate | currency |         brand          | audit_user_ok | source 
--------------+-------------+-----------+----------------+----------------+----------+------------------------+---------------+--------
 @shared_star | Shared Star | instagram |         250000 |        5000.00 | USD      | Aurora Beauty (client) | t             | manual
(1 row)

--- A.3 SAME HANDLE, TWO BRANDS: per-brand rows with independent rates ---
         brand          |    handle    | preferred_rate |                  id                  
------------------------+--------------+----------------+--------------------------------------
 Aurora Beauty (client) | @shared_star |        5000.00 | 2db37c7a-0076-4b52-8200-3a17e9ebd537
 Lumen Fitness (client) | @shared_star |        2000.00 | 211ec8e1-6da0-4925-b590-9cc13a0d3761
(2 rows)

--- A.4 campaign + assignment + coupon chain (all in Aurora) ---
        campaign         |  budget  | status | campaign_type | outreach_status | contract_status | agreed_fee |      code      | discount_type | discount_value | is_active 
-------------------------+----------+--------+---------------+-----------------+-----------------+------------+----------------+---------------+----------------+-----------
 Aurora Summer Glow 2026 | 50000.00 | active | paid          | new             | not_sent        |            | AURORA-STAR-20 | percentage    |          20.00 | t
(1 row)

--- A.5 every Aurora row carries the same brand_id (tenancy stamp) ---
   entity   |               brand_id               
------------+--------------------------------------
 creator    | dededede-0000-0000-0000-0000000000b1
 campaign   | dededede-0000-0000-0000-0000000000b1
 assignment | dededede-0000-0000-0000-0000000000b1
 coupon     | dededede-0000-0000-0000-0000000000b1
 board      | dededede-0000-0000-0000-0000000000b1
 card       | dededede-0000-0000-0000-0000000000b1
(6 rows)

--- A.6 workflow board, its 7 stages, and the card's current stage ---
          board          | is_active | stages 
-------------------------+-----------+--------
 Aurora Creator Pipeline | t         |      7
(1 row)

 position |  stage_name   
----------+---------------
        0 | Prospect
        1 | Outreach
        2 | Negotiation
        3 | Contracted
        4 | In Production
        5 | Published
        6 | Paid
(7 rows)

            card             | now_in_stage | position | agreed_fee | fee_currency | status 
-----------------------------+--------------+----------+------------+--------------+--------
 Negotiate with @shared_star | Outreach     |        0 |    5000.00 | USD          | todo
(1 row)

==================== J-B: SOLO BRAND OWNER (Veridian) ====================
--- B.1 signup auto-provisioned account + brand + OWNER membership ---
               email               |    brand_name    |     account      | account_type | role  | status |      brand       
-----------------------------------+------------------+------------------+--------------+-------+--------+------------------
 e2e.brand.owner@veridianglow.test | Veridian Glow Co | Veridian Glow Co | brand        | OWNER | active | Veridian Glow Co
(1 row)

--- B.2 creator + campaign + assignment + coupon ---
     handle     | platform | follower_count | preferred_rate 
----------------+----------+----------------+----------------
 @veridian_muse | youtube  |          88000 |        1500.00
(1 row)

      campaign      |  budget  | campaign_type | status | agreed_fee | outreach_status | contract_status 
--------------------+----------+---------------+--------+------------+-----------------+-----------------
 Veridian Launch Q3 | 12000.00 | affiliate     | active |            | new             | not_sent
(1 row)

       code       | discount_type | discount_value | commission_type | commission_value | is_active | sync_status 
------------------+---------------+----------------+-----------------+------------------+-----------+-------------
 VERIDIAN-MUSE-15 | percentage    |          15.00 | percent         |            12.00 | t         | local
(1 row)

--- B.3 MONEY CHAIN: order -> attribution -> commission -> payout ---
  order_id   | sale_amount | discount_amount | currency | attribution_status |    via_coupon    
-------------+-------------+-----------------+----------+--------------------+------------------
 E2E-ORDER-1 |      420.00 |           63.00 | USD      | attributed         | VERIDIAN-MUSE-15
(1 row)

                  id                  | gross_sale | commission_amount | status | currency |              payout_id               
--------------------------------------+------------+-------------------+--------+----------+--------------------------------------
 ccc763b9-6414-410e-bb72-29b7135512df |     420.00 |             50.40 | paid   | USD      | 215d4ed9-5c9e-43aa-b5d2-9cd26ab26863
(1 row)

                  id                  | total_amount | currency | status | provider_key |              creator_id              
--------------------------------------+--------------+----------+--------+--------------+--------------------------------------
 215d4ed9-5c9e-43aa-b5d2-9cd26ab26863 |        50.40 | USD      | paid   | manual       | 6361eaf6-ccdf-4212-94c4-7591c6e910a6
(1 row)

--- B.4 commission arithmetic: 420.00 sale x 12% = 50.40 ---
 sale_amount | commission_type | commission_value | commission_amount | expected | matches 
-------------+-----------------+------------------+-------------------+----------+---------
      420.00 | percent         |            12.00 |             50.40 |    50.40 | t
(1 row)

--- B.5 brand's workflow board + stages + card ---
       board       | stages 
-------------------+--------
 Veridian Outreach |      7
(1 row)

         card         | stage_name | agreed_fee | status 
----------------------+------------+------------+--------
 Brief @veridian_muse | Prospect   |    1500.00 | todo
(1 row)

--- B.6 campaign brief (content context) ---
 status | content 
--------+---------
 draft  | {}
(1 row)

==================== J-C: TENANCY PROOF ====================
--- C.1 zero rows leak across the two tenants ---
              check_name              | must_be_zero 
--------------------------------------+--------------
 agency rows visible under solo brand |            0
(1 row)

              check_name              | must_be_zero 
--------------------------------------+--------------
 solo rows visible under agency brand |            0
(1 row)

--- C.2 no row anywhere is missing its tenancy key ---
         t         | null_brand_id 
-------------------+---------------
 creators          |             0
 campaigns         |             0
 campaign_creators |             0
 coupons           |             0
 commissions       |             0
 payouts           |             0
 workflow_boards   |             0
 workflow_cards    |             0
(8 rows)

--- C.3 the MARKETER's write landed in Aurora, under their own identity ---
    handle    |         brand          |          created_by          
--------------+------------------------+------------------------------
 @marketer_ok | Aurora Beauty (client) | demo.marketer@northstar.test
(1 row)

--- C.4 sessions are server-side in Redis, nothing in the browser ---
dps:session:nUDSsuut0lPE5KcE3Xs1wa7IkY1eVpb6hqsfhsVHxps
dps:session:OphxFrgYIHMMEEYDPKWPiJYLxQMjxYe0dkKHXEnoExc
dps:session:6u9qqQtDWafG8zgnNny6NPh_WHdCh7V-TjhNYAiP-1E
dps:session:2dffmZVoDOC_p1c_tfLxG9V6mcxtAlKA2P5YmJvRpHk
dps:session:r4WVu-aztohgsdSJbCSmG1UcEKXXaqDQqFi6-0Mhs-4
redis session keys: 69
```

---

## 4. Reading of the results

**Tenancy holds.** A.5 shows six entity types created in the agency journey all carrying
`dededede-…b1`. C.2 shows zero NULL `brand_id` values across all eight tenant-scoped tables. C.1
shows zero rows visible across the tenant boundary in either direction.

**Per-brand creator rows behave as designed.** A.3 shows `@shared_star` existing twice — 5000.00
under Aurora, 2000.00 under Lumen — as two distinct ids. Negotiated terms do not bleed between
agency clients.

**The money chain is correct by value.** B.3 and B.4 trace one 420.00 order through attribution to a
50.40 commission and a 50.40 payout, with `420.00 × 12% = 50.40` and `matches = true`. The
commission's `payout_id` links the obligation to its settlement.

**The audit trail now resolves.** A.2 reports `audit_user_ok = t` and C.3 attributes `@marketer_ok`
to `demo.marketer@northstar.test` — both a consequence of the fix in §4.4 of the test report. Before
it, these columns were NULL for every row written through this path.

**Sessions are server-side.** C.4 lists `dps:session:*` keys in Redis. The browser holds only an
httpOnly cookie, and after the fix in §4.3 of the test report, no token at all.

---

## 5. Cleaning up this run's data

```sql
DELETE FROM workflow.workflow_cards WHERE name IN ('Negotiate with @shared_star','Brief @veridian_muse');
DELETE FROM workflow.workflow_board_stages WHERE board_id IN (
  SELECT id FROM workflow.workflow_boards WHERE name IN ('Aurora Creator Pipeline','Veridian Outreach'));
DELETE FROM workflow.workflow_boards WHERE name IN ('Aurora Creator Pipeline','Veridian Outreach');
DELETE FROM campaign.campaign_briefs WHERE brand_id IN (
  SELECT id FROM identity.brands WHERE name='Veridian Glow Co');
UPDATE finance.influencer_commissions SET payout_id=NULL WHERE brand_id IN (
  SELECT id FROM identity.brands WHERE name='Veridian Glow Co');
DELETE FROM finance.influencer_payouts     WHERE brand_id IN (SELECT id FROM identity.brands WHERE name='Veridian Glow Co');
DELETE FROM finance.influencer_commissions WHERE brand_id IN (SELECT id FROM identity.brands WHERE name='Veridian Glow Co');
DELETE FROM attribution.influencer_sale_attributions WHERE brand_id IN (
  SELECT id FROM identity.brands WHERE name='Veridian Glow Co');
DELETE FROM attribution.influencer_campaign_codes WHERE code IN ('AURORA-STAR-20','VERIDIAN-MUSE-15');
DELETE FROM creator.campaign_creators WHERE campaign_id IN (
  SELECT id FROM campaign.campaigns WHERE name IN ('Aurora Summer Glow 2026','Veridian Launch Q3'));
DELETE FROM campaign.campaigns WHERE name IN ('Aurora Summer Glow 2026','Veridian Launch Q3');
DELETE FROM creator.creators   WHERE handle IN ('@shared_star','@veridian_muse','@marketer_ok');
```

The signup account `e2e.brand.owner@veridianglow.test` is left in place so the journey can be re-run
without re-provisioning; delete the user to remove it and its account/brand by cascade.
