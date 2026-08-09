-- =============================================================
-- ROLLBACK: DDD Phase 1 (accounts / brands / memberships)
-- Date: 2026-08-02
-- Reverses: schema/migrations/2026_08_02_accounts_brands_memberships.sql
--
-- Safe to run ONLY while Phase 1 is the deployed state - that is, while user_id is
-- still the live tenancy key and nothing reads brand_id. Phase 1 was designed to be
-- reversible precisely so this script exists.
--
-- After Phase 2 ships (runtime switched to brand_id, user_id dropped) this script is
-- NO LONGER a valid rollback: brand_id becomes the only tenancy key and removing it
-- would orphan every domain row. At that point recovery is restore-from-backup.
--
-- What it removes:
--   * brand_id column + FK + indexes on all 18 domain tables
--   * the brand-keyed unique constraints
--   * created_by_user_id audit column
--   * accounts / brands / memberships / brand_access tables
--   * the account_role enum
--
-- What it does NOT touch:
--   * user_id on any table (never modified by Phase 1)
--   * any domain row (Phase 1 only added columns)
--   * users.brand_name / users.role / users.plan (Phase 2 removes those)
--
-- Usage:
--   docker exec -i influencercrm-postgres psql -U influencercrm_user \
--     -d influencercrm_db -v ON_ERROR_STOP=1 \
--     -f - < schema/rollback/2026_08_02_accounts_brands_memberships_rollback.sql
-- =============================================================

-- Refuse to run if the runtime has already moved on. If user_id is gone from the
-- domain tables then Phase 2 has shipped and this script would destroy tenancy.
do $$
declare
    domain_tables_with_user_id bigint;
begin
    select count(*) into domain_tables_with_user_id
      from information_schema.columns
     where table_schema = 'public'
       and column_name = 'user_id'
       and table_name in (
           'creators','campaigns','campaign_creators','interactions','import_batches',
           'workflow_boards','workflow_board_stages','workflow_cards',
           'influencer_campaign_codes','influencer_sale_attributions',
           'influencer_commissions','influencer_payouts','daily_attribution_stats',
           'marketplace_connections','campaign_briefs','landing_templates',
           'landing_page_views','mapping_examples'
       );

    if domain_tables_with_user_id <> 18 then
        raise exception
            'ABORT: user_id is present on only % of 18 domain tables. Phase 2 appears to '
            'have shipped, which makes brand_id the only tenancy key. Rolling back now '
            'would orphan every domain row. Restore from backup instead.',
            domain_tables_with_user_id;
    end if;
end $$;

-- 0) Drop the bridge triggers first: they reference brands, so they must go before
--    the columns and tables they depend on.
do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views','mapping_examples'
    ];
begin
    foreach t in array tenant_tables loop
        execute format('drop trigger if exists trg_sync_brand_tenancy on %I', t);
    end loop;
end $$;

drop trigger if exists trg_provision_tenancy_for_user on users;
drop function if exists sync_brand_tenancy();
drop function if exists provision_tenancy_for_user();

-- 1) Drop the brand-keyed unique constraints added by Phase 1.
--    (The legacy user_id ones were never dropped, so tenancy protection is unbroken.)
drop index if exists uq_creators_brand_platform_handle;
drop index if exists uq_icc_brand_code;
drop index if exists uq_das_grain_brand;

-- 2) Drop the mirrored composite indexes.
drop index if exists idx_das_brand_day;
drop index if exists idx_ic_brand_status;
drop index if exists idx_ip_brand_status;
drop index if exists idx_mc_brand_provider;
drop index if exists idx_workflow_boards_brand_position;

-- 3) Drop brand_id and created_by_user_id from every domain table.
do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views','mapping_examples'
    ];
begin
    foreach t in array tenant_tables loop
        execute format('alter table %I drop constraint if exists %I', t, 'fk_' || t || '_brand');
        execute format('alter table %I drop constraint if exists %I', t, 'fk_' || t || '_created_by');
        execute format('drop index if exists %I', 'idx_' || t || '_brand');
        -- Dropping the column discards only Phase 1 derived data; user_id still holds tenancy.
        execute format('alter table %I drop column if exists brand_id', t);
        execute format('alter table %I drop column if exists created_by_user_id', t);
    end loop;
end $$;

-- 4) Drop the tenancy spine. Order matters: children first.
drop table if exists brand_access;
drop table if exists memberships;
drop table if exists brands;
drop table if exists accounts;

-- 5) Drop the enum once nothing references it.
do $$
begin
    if exists (select 1 from pg_type where typname = 'account_role') then
        drop type account_role;
    end if;
end $$;

-- 6) Confirm the pre-Phase-1 shape is restored.
do $$
declare
    leftover bigint;
begin
    select count(*) into leftover from information_schema.columns
     where table_schema = 'public' and column_name in ('brand_id','created_by_user_id');
    if leftover > 0 then
        raise exception 'Rollback incomplete: % brand_id/created_by_user_id column(s) remain', leftover;
    end if;

    select count(*) into leftover from information_schema.tables
     where table_schema = 'public' and table_name in ('accounts','brands','memberships','brand_access');
    if leftover > 0 then
        raise exception 'Rollback incomplete: % tenancy table(s) remain', leftover;
    end if;

    raise notice 'Phase 1 rollback complete - database restored to the pre-migration shape.';
end $$;
