-- =============================================================
-- Migration: Phase 2 cutover to brand tenancy
-- Date: 2026-08-02
-- Purpose:
--   Complete the switch from user_id to brand_id as the live tenancy key.
--
--   Phase 1 added brand_id alongside user_id and populated it, changing no behaviour.
--   The application now reads and writes brand_id, so this migration removes the
--   legacy constraints that still key tenancy on user_id.
--
-- CRITICAL — why the constraint drops belong in THIS release:
--   creators_user_id_platform_handle_key and uq_influencer_campaign_codes_user_code
--   reject the very thing the agency model exists to allow: two brands under one
--   account holding the same creator handle, or issuing the same coupon code. Both
--   rows share one owning user_id, so the legacy constraint fires.
--
--   Leaving them in place until "later" would ship an agency feature that appears
--   broken the first time an agency uses it. They must go in the same release that
--   switches the runtime to brand_id — which is this one.
--
--   Their brand-keyed replacements (uq_creators_brand_platform_handle,
--   uq_icc_brand_code, uq_das_grain_brand) were created in Phase 1 and are already
--   enforcing correctness, so nothing is unprotected at any point.
--
-- What this migration deliberately does NOT do:
--   * It does not drop user_id. The Phase 1 bridge triggers still keep user_id and
--     brand_id in step, so the rollback path survives one more release. Dropping the
--     columns is a separate, later step once brand tenancy has soaked in production.
--   * It does not drop users.brand_name / role / plan for the same reason.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- =============================================================
-- 1) Drop the legacy user-keyed unique constraints
-- =============================================================
-- Safety check first: refuse to unlock multi-brand unless the brand-keyed
-- replacements actually exist. Dropping the old constraint without the new one would
-- leave duplicate handles and duplicate codes completely unguarded.
do $$
declare
    missing text[] := '{}';
begin
    if not exists (select 1 from pg_indexes where schemaname = 'public'
                    and indexname = 'uq_creators_brand_platform_handle') then
        missing := missing || 'uq_creators_brand_platform_handle';
    end if;
    if not exists (select 1 from pg_indexes where schemaname = 'public'
                    and indexname = 'uq_icc_brand_code') then
        missing := missing || 'uq_icc_brand_code';
    end if;
    if not exists (select 1 from pg_indexes where schemaname = 'public'
                    and indexname = 'uq_das_grain_brand') then
        missing := missing || 'uq_das_grain_brand';
    end if;

    if array_length(missing, 1) > 0 then
        raise exception
            'ABORT: brand-keyed unique constraints are missing (%). Run the Phase 1 '
            'migration first — dropping the user-keyed constraints now would leave '
            'duplicates unguarded.', array_to_string(missing, ', ');
    end if;
end $$;

alter table creators
    drop constraint if exists creators_user_id_platform_handle_key;

alter table influencer_campaign_codes
    drop constraint if exists uq_influencer_campaign_codes_user_code;

alter table daily_attribution_stats
    drop constraint if exists uq_das_grain;

-- =============================================================
-- 2) Relax the NOT NULL on the legacy tenancy column
-- =============================================================
-- The application no longer sets user_id; the Phase 1 bridge trigger back-fills it
-- from brand_id. That trigger runs BEFORE INSERT, so the value is present by the
-- time constraints are checked — but a row whose brand has no legacy_user_id (any
-- brand created after Phase 1, i.e. every agency brand) legitimately has no user to
-- map back to. Those rows must be allowed to carry a null user_id.
do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views'
        -- mapping_examples.user_id is already nullable.
    ];
begin
    foreach t in array tenant_tables loop
        execute format('alter table %I alter column user_id drop not null', t);
    end loop;
end $$;

-- =============================================================
-- 3) Post-conditions
-- =============================================================
do $$
declare
    leftover bigint;
begin
    -- The legacy constraints must be gone, or agencies still cannot share a creator.
    select count(*) into leftover from pg_indexes
     where schemaname = 'public'
       and indexname in ('creators_user_id_platform_handle_key',
                         'uq_influencer_campaign_codes_user_code',
                         'uq_das_grain');
    if leftover > 0 then
        raise exception 'Phase 2: % legacy user-keyed constraint(s) still present', leftover;
    end if;

    -- The brand-keyed replacements must still be in force.
    select count(*) into leftover from pg_indexes
     where schemaname = 'public'
       and indexname in ('uq_creators_brand_platform_handle',
                         'uq_icc_brand_code',
                         'uq_das_grain_brand');
    if leftover <> 3 then
        raise exception 'Phase 2: expected 3 brand-keyed unique constraints, found %', leftover;
    end if;

    -- Every domain row must still have a brand.
    select count(*) into leftover from creators where brand_id is null;
    if leftover > 0 then
        raise exception 'Phase 2: % creator row(s) have no brand_id', leftover;
    end if;

    raise notice 'Phase 2 cutover OK: brand_id is now the live tenancy key; '
                 'agencies may share creators and codes across brands.';
end $$;
