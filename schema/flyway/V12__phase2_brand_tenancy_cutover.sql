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
    -- The ::text casts below are NOT decoration. `text[] || 'literal'` is ambiguous: an unadorned string
    -- literal is untyped, so Postgres resolves the operator to array || array and tries to parse the
    -- literal AS an array — failing with `malformed array literal: "uq_creators_brand_platform_handle"`.
    -- The cast picks the array || element overload instead.
    --
    -- This only fires on a RE-RUN, because on a fresh database the indexes are missing and the appends
    -- never execute. That is exactly why it survived the first deploy and broke the second.
    --
    -- NO `schemaname = 'public'` FILTER, deliberately. These indexes are created in public by the Phase 1
    -- migration, but the later phase-5 schema-per-context migration MOVES their tables into `creator` and
    -- `attribution`. On a re-run the indexes therefore exist under those schemas, a public-only lookup
    -- finds nothing, and this guard aborts the deploy claiming Phase 1 never ran. The question it means
    -- to ask is "does this index exist", and index names are unique enough here to ask it directly.
    if not exists (select 1 from pg_indexes where indexname = 'uq_creators_brand_platform_handle') then
        missing := missing || 'uq_creators_brand_platform_handle'::text;
    end if;
    if not exists (select 1 from pg_indexes where indexname = 'uq_icc_brand_code') then
        missing := missing || 'uq_icc_brand_code'::text;
    end if;
    if not exists (select 1 from pg_indexes where indexname = 'uq_das_grain_brand') then
        missing := missing || 'uq_das_grain_brand'::text;
    end if;

    if array_length(missing, 1) > 0 then
        raise exception
            'ABORT: brand-keyed unique constraints are missing (%). Run the Phase 1 '
            'migration first — dropping the user-keyed constraints now would leave '
            'duplicates unguarded.', array_to_string(missing, ', ');
    end if;
end $$;

-- Dropped by CONSTRAINT NAME wherever it lives, not by `alter table <unqualified>`.
--
-- An unqualified `alter table creators` resolves through search_path and hits exactly one table. On a
-- re-run the base schema recreates `public.creators` — inline `unique (user_id, platform, handle)` and
-- all — while the live table sits in `creator`. The unqualified form then drops the constraint from
-- whichever one it found and leaves the other, and the verification block below fails with
-- "1 legacy user-keyed constraint(s) still present" on a database that is otherwise correct.
do $$
declare r record;
begin
    for r in
        select n.nspname as sch, t.relname as tbl, c.conname
          from pg_constraint c
          join pg_class t on t.oid = c.conrelid
          join pg_namespace n on n.oid = t.relnamespace
         where c.conname in ('creators_user_id_platform_handle_key',
                             'uq_influencer_campaign_codes_user_code',
                             'uq_das_grain')
    loop
        execute format('alter table %I.%I drop constraint %I', r.sch, r.tbl, r.conname);
        raise notice 'dropped legacy constraint %.%.%', r.sch, r.tbl, r.conname;
    end loop;
end $$;

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
    -- The legacy constraints must be gone, or agencies still cannot share a creator. Unfiltered by
    -- schema: this asserts an ABSENCE, and a public-only check would report success simply because
    -- phase-5 had relocated a constraint that is in fact still there.
    select count(*) into leftover from pg_indexes
     where indexname in ('creators_user_id_platform_handle_key',
                         'uq_influencer_campaign_codes_user_code',
                         'uq_das_grain');
    if leftover > 0 then
        raise exception 'Phase 2: % legacy user-keyed constraint(s) still present', leftover;
    end if;

    -- The brand-keyed replacements must still be in force. No schemaname filter, for the same reason as
    -- the guard at the top of this file: phase-5 moves these tables into the `creator` and `attribution`
    -- schemas, so a public-only count returns 0 on a re-run and aborts a deploy that is actually fine.
    -- DISTINCT on the name, not a raw row count. On a re-run the Phase 1 migration recreates these in
    -- `public` while phase-5's relocated copies still exist in the context schemas, so the same three
    -- names appear twice and a plain count(*) returns 6 — failing an assertion that is actually
    -- satisfied. The question is "are all three present", so count the names.
    select count(distinct indexname) into leftover from pg_indexes
     where indexname in ('uq_creators_brand_platform_handle',
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
