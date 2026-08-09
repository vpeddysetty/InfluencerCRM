-- =============================================================
-- Verification: DDD Phase 1 reconciliation
-- Date: 2026-08-02
-- Purpose:
--   Prove that 2026_08_02_accounts_brands_memberships.sql re-keyed every domain
--   row to the correct brand, with no drift and no orphans.
--
--   Phase 1's exit criteria (docs/ddd-roadmap.md):
--     * every domain row has a valid brand_id
--     * per-brand row counts match the prior per-user counts
--     * the app still runs on user_id (this migration changes no behaviour)
--
-- Usage:
--   docker exec -i influencercrm-postgres psql -U influencercrm_user \
--     -d influencercrm_db -f - < schema/verify/2026_08_02_phase1_reconciliation.sql
--
-- Read-only. Safe to run any number of times, including in production.
-- Every check below prints PASS or FAIL; check 6 aborts on failure.
-- =============================================================

\echo ''
\echo '=== 1. Tenancy spine: one account + one brand + one OWNER membership per user ==='

select
    (select count(*) from users)                                        as users,
    (select count(*) from accounts where legacy_user_id is not null)    as accounts,
    (select count(*) from brands   where legacy_user_id is not null)    as brands,
    (select count(*) from memberships)                                  as memberships,
    (select count(*) from memberships where role = 'OWNER')             as owner_memberships,
    case
        when (select count(*) from users) = (select count(*) from accounts where legacy_user_id is not null)
         and (select count(*) from users) = (select count(*) from brands   where legacy_user_id is not null)
         and (select count(*) from users) = (select count(*) from memberships where role = 'OWNER')
        then 'PASS' else 'FAIL'
    end as result;

\echo ''
\echo '=== 2. Row-count drift: per-user count vs per-brand count, per table ==='
\echo '    (drift must be 0 everywhere; a non-zero value means rows changed owner)'

with per_table as (
    select 'creators' as tbl,
           (select count(*) from creators where user_id is not null)  as by_user,
           (select count(*) from creators where brand_id is not null) as by_brand
    union all select 'campaigns',
           (select count(*) from campaigns where user_id is not null),
           (select count(*) from campaigns where brand_id is not null)
    union all select 'campaign_creators',
           (select count(*) from campaign_creators where user_id is not null),
           (select count(*) from campaign_creators where brand_id is not null)
    union all select 'interactions',
           (select count(*) from interactions where user_id is not null),
           (select count(*) from interactions where brand_id is not null)
    union all select 'import_batches',
           (select count(*) from import_batches where user_id is not null),
           (select count(*) from import_batches where brand_id is not null)
    union all select 'workflow_boards',
           (select count(*) from workflow_boards where user_id is not null),
           (select count(*) from workflow_boards where brand_id is not null)
    union all select 'workflow_board_stages',
           (select count(*) from workflow_board_stages where user_id is not null),
           (select count(*) from workflow_board_stages where brand_id is not null)
    union all select 'workflow_cards',
           (select count(*) from workflow_cards where user_id is not null),
           (select count(*) from workflow_cards where brand_id is not null)
    union all select 'influencer_campaign_codes',
           (select count(*) from influencer_campaign_codes where user_id is not null),
           (select count(*) from influencer_campaign_codes where brand_id is not null)
    union all select 'influencer_sale_attributions',
           (select count(*) from influencer_sale_attributions where user_id is not null),
           (select count(*) from influencer_sale_attributions where brand_id is not null)
    union all select 'influencer_commissions',
           (select count(*) from influencer_commissions where user_id is not null),
           (select count(*) from influencer_commissions where brand_id is not null)
    union all select 'influencer_payouts',
           (select count(*) from influencer_payouts where user_id is not null),
           (select count(*) from influencer_payouts where brand_id is not null)
    union all select 'daily_attribution_stats',
           (select count(*) from daily_attribution_stats where user_id is not null),
           (select count(*) from daily_attribution_stats where brand_id is not null)
    union all select 'marketplace_connections',
           (select count(*) from marketplace_connections where user_id is not null),
           (select count(*) from marketplace_connections where brand_id is not null)
    union all select 'campaign_briefs',
           (select count(*) from campaign_briefs where user_id is not null),
           (select count(*) from campaign_briefs where brand_id is not null)
    union all select 'landing_templates',
           (select count(*) from landing_templates where user_id is not null),
           (select count(*) from landing_templates where brand_id is not null)
    union all select 'landing_page_views',
           (select count(*) from landing_page_views where user_id is not null),
           (select count(*) from landing_page_views where brand_id is not null)
    union all select 'mapping_examples',
           (select count(*) from mapping_examples where user_id is not null),
           (select count(*) from mapping_examples where brand_id is not null)
)
select tbl, by_user, by_brand, by_user - by_brand as drift,
       case when by_user = by_brand then 'PASS' else 'FAIL' end as result
from per_table
order by case when by_user = by_brand then 1 else 0 end, tbl;

\echo ''
\echo '=== 3. Every row maps to the brand owned by its own user (no cross-tenant reassignment) ==='
\echo '    (mismatched = rows whose brand belongs to a DIFFERENT user than user_id)'

with mismatches as (
    select 'creators' as tbl, count(*) as bad from creators t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'campaigns', count(*) from campaigns t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'campaign_creators', count(*) from campaign_creators t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'interactions', count(*) from interactions t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'import_batches', count(*) from import_batches t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'workflow_boards', count(*) from workflow_boards t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'workflow_board_stages', count(*) from workflow_board_stages t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'workflow_cards', count(*) from workflow_cards t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'influencer_campaign_codes', count(*) from influencer_campaign_codes t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'influencer_sale_attributions', count(*) from influencer_sale_attributions t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'influencer_commissions', count(*) from influencer_commissions t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'influencer_payouts', count(*) from influencer_payouts t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'daily_attribution_stats', count(*) from daily_attribution_stats t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'marketplace_connections', count(*) from marketplace_connections t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'campaign_briefs', count(*) from campaign_briefs t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'landing_templates', count(*) from landing_templates t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'landing_page_views', count(*) from landing_page_views t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
    union all select 'mapping_examples', count(*) from mapping_examples t join brands b on b.id = t.brand_id where b.legacy_user_id <> t.user_id
)
select coalesce(sum(bad), 0) as mismatched_rows,
       case when coalesce(sum(bad), 0) = 0 then 'PASS' else 'FAIL' end as result
from mismatches;

\echo ''
\echo '=== 4. Constraints and indexes required by Phase 2 ==='

select expected.name, expected.kind,
       case when pg_indexes.indexname is not null then 'PASS' else 'FAIL' end as result
from (values
    ('uq_creators_brand_platform_handle', 'unique: creators per brand (plan 3.4)'),
    ('uq_icc_brand_code',                 'unique: campaign code per brand'),
    ('uq_das_grain_brand',                'unique: daily stats grain per brand'),
    ('idx_das_brand_day',                 'index: dashboard rollup'),
    ('idx_ic_brand_status',               'index: commission list by status'),
    ('idx_ip_brand_status',               'index: payout list by status'),
    ('idx_mc_brand_provider',             'index: marketplace lookup'),
    ('idx_workflow_boards_brand_position','index: board ordering')
) as expected(name, kind)
left join pg_indexes on pg_indexes.indexname = expected.name and pg_indexes.schemaname = 'public'
order by case when pg_indexes.indexname is not null then 1 else 0 end, expected.name;

\echo ''
\echo '=== 4b. Bridge triggers present on all 18 tables + users ==='
\echo '    (without these, NEW rows get no brand_id and every write fails)'

select
    (select count(*) from pg_trigger where tgname = 'trg_sync_brand_tenancy' and not tgisinternal) as sync_triggers,
    (select count(*) from pg_trigger where tgname = 'trg_provision_tenancy_for_user' and not tgisinternal) as provision_trigger,
    case
        when (select count(*) from pg_trigger where tgname = 'trg_sync_brand_tenancy' and not tgisinternal) = 18
         and (select count(*) from pg_trigger where tgname = 'trg_provision_tenancy_for_user' and not tgisinternal) = 1
        then 'PASS' else 'FAIL'
    end as result;

\echo ''
\echo '=== 4c. Every user has a brand (new signups included) ==='

select count(*) as users_without_brand,
       case when count(*) = 0 then 'PASS' else 'FAIL' end as result
from users u
where not exists (select 1 from brands b where b.legacy_user_id = u.id);

\echo ''
\echo '=== 5. Rollback path intact: user_id must still be present on all 18 domain tables ==='
\echo '    (Phase 1 is reversible ONLY while user_id survives - Phase 2 drops it)'
\echo '    (memberships.user_id is excluded: it is the new spine, not legacy tenancy)'

select count(*) as domain_tables_with_user_id,
       case when count(*) = 18 then 'PASS' else 'FAIL' end as result
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

\echo ''
\echo '=== 6. Overall verdict ==='

do $$
declare
    failures text[] := '{}';
    n bigint;
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views'
        -- mapping_examples excluded: its user_id is nullable by design, so a null
        -- brand_id there is expected rather than a defect.
    ];
begin
    -- a) one account/brand/owner-membership per user
    select count(*) into n from users;
    if (select count(*) from accounts where legacy_user_id is not null) <> n then
        failures := failures || array['account count != user count'];
    end if;
    if (select count(*) from brands where legacy_user_id is not null) <> n then
        failures := failures || array['brand count != user count'];
    end if;
    if (select count(*) from memberships where role = 'OWNER') <> n then
        failures := failures || array['OWNER membership count != user count'];
    end if;

    -- b) no unmapped rows
    foreach t in array tenant_tables loop
        execute format('select count(*) from %I where brand_id is null', t) into n;
        if n > 0 then
            failures := failures || array[format('%s has %s row(s) with null brand_id', t, n)];
        end if;
    end loop;

    -- c) no row reassigned to another user's brand
    foreach t in array tenant_tables loop
        execute format(
            'select count(*) from %I t join brands b on b.id = t.brand_id
              where b.legacy_user_id <> t.user_id', t) into n;
        if n > 0 then
            failures := failures || array[format('%s has %s row(s) mapped to another user''s brand', t, n)];
        end if;
    end loop;

    -- c2) bridge triggers must exist, or new writes fail with a not-null violation
    select count(*) into n from pg_trigger
     where tgname = 'trg_sync_brand_tenancy' and not tgisinternal;
    if n <> 18 then
        failures := failures || array[format('expected trg_sync_brand_tenancy on 18 tables, found %s', n)];
    end if;

    select count(*) into n from pg_trigger
     where tgname = 'trg_provision_tenancy_for_user' and not tgisinternal;
    if n <> 1 then
        failures := failures || array['trg_provision_tenancy_for_user is missing from users'];
    end if;

    -- c3) every user must resolve to a brand, including post-backfill signups
    select count(*) into n from users u
     where not exists (select 1 from brands b where b.legacy_user_id = u.id);
    if n > 0 then
        failures := failures || array[format('%s user(s) have no brand', n)];
    end if;

    -- d) rollback path. Counts only the legacy domain tables; memberships.user_id
    --    belongs to the new tenancy spine and must not be counted here.
    select count(*) into n from information_schema.columns
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
    if n <> 18 then
        failures := failures || array[format('expected user_id on all 18 domain tables, found %s', n)];
    end if;

    if array_length(failures, 1) > 0 then
        raise exception E'PHASE 1 RECONCILIATION FAILED:\n  - %', array_to_string(failures, E'\n  - ');
    end if;

    raise notice '';
    raise notice 'PHASE 1 RECONCILIATION PASSED - safe to proceed to Phase 2.';
    raise notice '';
end $$;
