-- =============================================================
-- Migration: Schema-per-context  (DDD Phase 5 prerequisite, deferred from 4.5)
-- Date: 2026-08-02
-- Purpose:
--   Give each bounded context its own Postgres schema, so a context can later be
--   granted credentials that reach only its own tables.
--
--   Phase 4 enforced the boundaries in Java (ArchUnit). That stops a developer
--   crossing a boundary, but it does not stop a *runtime* from doing so: every
--   context still connects as one role with access to all 24 tables. Schema
--   separation is what makes "this service can only see its own data" enforceable
--   by the database rather than by convention.
--
-- Approach — search_path, not table moves:
--   Tables are moved into per-context schemas, and the application role's
--   search_path is set so unqualified names still resolve. That keeps every
--   existing query working unchanged while making ownership explicit and
--   per-schema GRANTs possible.
--
--   The alternative (renaming every table reference in Java) buys nothing today
--   and would touch hundreds of lines for no behavioural gain.
--
-- Cross-context foreign keys:
--   Postgres allows FKs across schemas, and the existing ones are left in place.
--   They are the safety net while this is still one database. Phase 5 proper —
--   when a context becomes a separate service with its own database — is when
--   they get dropped and replaced by ID-only references plus events. Dropping
--   them now would remove referential integrity months before anything needs it.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- =============================================================
-- 1) One schema per bounded context
-- =============================================================
create schema if not exists identity;
create schema if not exists creator;
create schema if not exists campaign;
create schema if not exists workflow;
create schema if not exists attribution;
create schema if not exists finance;
create schema if not exists content;
create schema if not exists mapping;
create schema if not exists shared;

comment on schema identity    is 'Accounts, brands, memberships, users. The tenancy spine.';
comment on schema creator     is 'Creators, interactions, campaign_creators.';
comment on schema campaign    is 'Campaigns, briefs, import batches.';
comment on schema workflow    is 'Kanban boards, stages, cards.';
comment on schema attribution is 'Campaign codes, sale attributions, marketplace connections, daily stats.';
comment on schema finance     is 'Commissions and payouts. Money — strongest isolation.';
comment on schema content     is 'Landing templates and page views.';
comment on schema mapping     is 'AI column-mapping examples.';
comment on schema shared      is 'Cross-cutting infrastructure owned by no single context (the outbox).';

-- =============================================================
-- 2) Move each table to its owning context
-- =============================================================
-- Guarded per table: a re-run, or a table already moved by hand, must not fail
-- the migration.
do $$
declare
    target record;
begin
    for target in
        select * from (values
            ('users',                        'identity'),
            ('accounts',                     'identity'),
            ('brands',                       'identity'),
            ('memberships',                  'identity'),
            ('brand_access',                 'identity'),

            ('creators',                     'creator'),
            ('interactions',                 'creator'),
            ('campaign_creators',            'creator'),

            ('campaigns',                    'campaign'),
            ('campaign_briefs',              'campaign'),
            ('import_batches',               'campaign'),

            ('workflow_boards',              'workflow'),
            ('workflow_board_stages',        'workflow'),
            ('workflow_cards',               'workflow'),

            ('influencer_campaign_codes',    'attribution'),
            ('influencer_sale_attributions', 'attribution'),
            ('marketplace_connections',      'attribution'),
            ('daily_attribution_stats',      'attribution'),

            ('influencer_commissions',       'finance'),
            ('influencer_payouts',           'finance'),

            ('landing_templates',            'content'),
            ('landing_page_views',           'content'),

            ('mapping_examples',             'mapping'),

            ('domain_events',                'shared')
        ) as t(table_name, target_schema)
    loop
        if exists (
            select 1 from pg_tables
             where schemaname = 'public' and tablename = target.table_name
        ) then
            execute format('alter table public.%I set schema %I',
                           target.table_name, target.target_schema);
            raise notice 'moved % -> %', target.table_name, target.target_schema;
        end if;
    end loop;
end $$;

-- =============================================================
-- 3) Keep unqualified table names resolving
-- =============================================================
-- Every existing query says `select ... from creators`, not `creator.creators`.
-- Setting search_path on the role means none of them has to change, while the
-- schemas still express ownership and still allow per-schema GRANTs.
--
-- Order matters only for name collisions, and there are none: every table name
-- is unique across the contexts.
do $$
declare
    app_role text := current_user;
begin
    execute format(
        'alter role %I set search_path = identity, creator, campaign, workflow, '
        'attribution, finance, content, mapping, shared, public',
        app_role);
    raise notice 'search_path set for role %', app_role;
end $$;

-- Apply to the current session too, so the checks below (and anything else in
-- this connection) resolve names immediately rather than after a reconnect.
set search_path = identity, creator, campaign, workflow,
                  attribution, finance, content, mapping, shared, public;

-- =============================================================
-- 4) Post-conditions
-- =============================================================
do $$
declare
    stragglers text;
    moved bigint;
begin
    -- Every domain table must now live in a context schema.
    select string_agg(tablename, ', ') into stragglers
      from pg_tables
     where schemaname = 'public'
       and tablename in (
           'users','accounts','brands','memberships','brand_access',
           'creators','interactions','campaign_creators',
           'campaigns','campaign_briefs','import_batches',
           'workflow_boards','workflow_board_stages','workflow_cards',
           'influencer_campaign_codes','influencer_sale_attributions',
           'marketplace_connections','daily_attribution_stats',
           'influencer_commissions','influencer_payouts',
           'landing_templates','landing_page_views',
           'mapping_examples','domain_events');

    if stragglers is not null then
        raise exception 'Phase 5: these tables are still in public: %', stragglers;
    end if;

    select count(*) into moved
      from pg_tables
     where schemaname in ('identity','creator','campaign','workflow',
                          'attribution','finance','content','mapping','shared');

    if moved <> 24 then
        raise exception 'Phase 5: expected 24 tables across context schemas, found %', moved;
    end if;

    -- Unqualified resolution must still work, or every query in the app breaks.
    perform 1 from creators limit 1;
    perform 1 from brands   limit 1;
    perform 1 from domain_events limit 1;

    raise notice 'Phase 5 schema split OK: 24 tables across 9 context schemas; '
                 'unqualified names still resolve via search_path.';
end $$;
