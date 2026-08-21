-- =============================================================
-- Migration: Sever the remaining cross-context foreign keys  (DDD Phase 5)
-- Date: 2026-08-02
-- Purpose:
--   Complete the extraction prerequisite that the Workflow pilot identified as
--   step zero: a service with its own database cannot enforce a foreign key to a
--   table it cannot see.
--
--   Workflow was done in 2026_08_02_phase5_workflow_extraction.sql. This does the
--   same for the six remaining contexts.
--
-- What is dropped:
--   Foreign keys pointing at ANOTHER bounded context's tables. They become ID-only
--   references (migration plan section 5.2).
--
-- What is deliberately KEPT:
--   1) FKs to identity.brands and identity.users — the tenancy spine. Every context
--      validates a brand, and every service is granted SELECT on the spine precisely
--      so that check stays a local query rather than a network hop. These are dropped
--      only if Identity ever moves to a physically separate database.
--   2) FKs internal to a context (e.g. attribution's own tables referencing each
--      other). They stay enforceable after extraction.
--
-- Replacement safety net:
--   Every dropped FK trades an enforced guarantee for an unenforced assumption. A
--   per-context orphan-monitoring view replaces it, following the pattern proven in
--   the Workflow pilot: it should always be empty and is safe to alert on.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- =============================================================
-- 1) Report would-be orphans BEFORE dropping anything
-- =============================================================
-- A pre-existing integrity problem must not be silently inherited by a new service.
do $$
declare
    bad bigint;
    total bigint := 0;
begin
    select count(*) into bad from creator.campaign_creators cc
     where cc.campaign_id is not null
       and not exists (select 1 from campaign.campaigns x where x.id = cc.campaign_id);
    total := total + bad;
    if bad > 0 then raise warning 'campaign_creators: % row(s) reference a missing campaign', bad; end if;

    select count(*) into bad from attribution.influencer_campaign_codes c
     where c.creator_id is not null
       and not exists (select 1 from creator.creators x where x.id = c.creator_id);
    total := total + bad;
    if bad > 0 then raise warning 'influencer_campaign_codes: % row(s) reference a missing creator', bad; end if;

    select count(*) into bad from finance.influencer_commissions c
     where c.creator_id is not null
       and not exists (select 1 from creator.creators x where x.id = c.creator_id);
    total := total + bad;
    if bad > 0 then raise warning 'influencer_commissions: % row(s) reference a missing creator', bad; end if;

    select count(*) into bad from content.landing_templates t
     where t.campaign_id is not null
       and not exists (select 1 from campaign.campaigns x where x.id = t.campaign_id);
    total := total + bad;
    if bad > 0 then raise warning 'landing_templates: % row(s) reference a missing campaign', bad; end if;

    if total = 0 then
        raise notice 'No orphan cross-context references found before severing.';
    end if;
end $$;

-- =============================================================
-- 2) Drop every FK pointing outside its own context
-- =============================================================
-- Driven off the catalogue rather than a hand-written list, so a table added later
-- cannot be missed. The tenancy spine is excluded explicitly.
do $$
declare
    fk record;
    dropped int := 0;
begin
    for fk in
        select n.nspname  as owning_schema,
               t.relname  as owning_table,
               c.conname  as constraint_name,
               nf.nspname as target_schema,
               cf.relname as target_table
          from pg_constraint c
          join pg_class t       on t.oid  = c.conrelid
          join pg_namespace n   on n.oid  = t.relnamespace
          join pg_class cf      on cf.oid = c.confrelid
          join pg_namespace nf  on nf.oid = cf.relnamespace
         where c.contype = 'f'
           and n.nspname <> nf.nspname
           and n.nspname in ('identity','creator','campaign','workflow',
                             'attribution','finance','content','mapping')
           -- Keep the tenancy spine: every context legitimately reads it.
           and not (nf.nspname = 'identity' and cf.relname in ('brands','users'))
    loop
        execute format('alter table %I.%I drop constraint if exists %I',
                       fk.owning_schema, fk.owning_table, fk.constraint_name);
        raise notice 'dropped %.% -> %.% (%)',
              fk.owning_schema, fk.owning_table, fk.target_schema, fk.target_table, fk.constraint_name;
        dropped := dropped + 1;
    end loop;

    raise notice 'severed % cross-context foreign key(s)', dropped;
end $$;

-- =============================================================
-- 3) Replace the lost guarantees with orphan-monitoring views
-- =============================================================
-- One per context that now holds unenforced references. Each should always be empty.

create or replace view creator.orphaned_references as
select cc.id as campaign_creator_id, cc.brand_id, cc.campaign_id, cc.import_batch_id,
       (cc.campaign_id is not null
        and not exists (select 1 from campaign.campaigns x where x.id = cc.campaign_id)) as campaign_missing
  from creator.campaign_creators cc
 where cc.campaign_id is not null
   and not exists (select 1 from campaign.campaigns x where x.id = cc.campaign_id);

comment on view creator.orphaned_references is
    'campaign_creators rows whose campaign no longer exists. Replaces a dropped foreign key: '
    'should always be empty, and is safe to alert on.';

create or replace view attribution.orphaned_references as
select c.id as code_id, c.brand_id, c.campaign_id, c.creator_id,
       (c.creator_id is not null
        and not exists (select 1 from creator.creators x where x.id = c.creator_id)) as creator_missing,
       (c.campaign_id is not null
        and not exists (select 1 from campaign.campaigns x where x.id = c.campaign_id)) as campaign_missing
  from attribution.influencer_campaign_codes c
 where (c.creator_id is not null
        and not exists (select 1 from creator.creators x where x.id = c.creator_id))
    or (c.campaign_id is not null
        and not exists (select 1 from campaign.campaigns x where x.id = c.campaign_id));

comment on view attribution.orphaned_references is
    'Campaign codes whose creator or campaign no longer exists. Replaces dropped foreign keys.';

create or replace view finance.orphaned_references as
select k.id as commission_id, k.brand_id, k.creator_id, k.campaign_id,
       (k.creator_id is not null
        and not exists (select 1 from creator.creators x where x.id = k.creator_id)) as creator_missing
  from finance.influencer_commissions k
 where k.creator_id is not null
   and not exists (select 1 from creator.creators x where x.id = k.creator_id);

comment on view finance.orphaned_references is
    'Commissions whose creator no longer exists. Replaces a dropped foreign key. Money-adjacent: '
    'this one genuinely warrants an alert.';

create or replace view content.orphaned_references as
select t.id as template_id, t.brand_id, t.campaign_id,
       (t.campaign_id is not null
        and not exists (select 1 from campaign.campaigns x where x.id = t.campaign_id)) as campaign_missing
  from content.landing_templates t
 where t.campaign_id is not null
   and not exists (select 1 from campaign.campaigns x where x.id = t.campaign_id);

comment on view content.orphaned_references is
    'Landing templates whose campaign no longer exists. Replaces a dropped foreign key.';

-- =============================================================
-- 4) Let each service read what its own monitoring view needs
-- =============================================================
-- A service cannot query its own orphan view without SELECT on the referenced tables.
-- Read-only: writes stay strictly owned.
do $$
declare
    grant_spec record;
begin
    for grant_spec in
        select * from (values
            ('svc_creator',     'campaign', 'campaign.campaigns'),
            ('svc_attribution', 'creator',  'creator.creators'),
            ('svc_attribution', 'campaign', 'campaign.campaigns'),
            ('svc_finance',     'creator',  'creator.creators'),
            ('svc_content',     'campaign', 'campaign.campaigns')
        ) as t(role_name, target_schema, target_table)
    loop
        if exists (select 1 from pg_roles where rolname = grant_spec.role_name) then
            execute format('grant usage on schema %I to %I', grant_spec.target_schema, grant_spec.role_name);
            execute format('grant select on %s to %I', grant_spec.target_table, grant_spec.role_name);
        end if;
    end loop;

    for grant_spec in
        select * from (values
            ('svc_creator', 'creator.orphaned_references'),
            ('svc_attribution', 'attribution.orphaned_references'),
            ('svc_finance', 'finance.orphaned_references'),
            ('svc_content', 'content.orphaned_references')
        ) as t(role_name, view_name)
    loop
        if exists (select 1 from pg_roles where rolname = grant_spec.role_name) then
            execute format('grant select on %s to %I', grant_spec.view_name, grant_spec.role_name);
        end if;
    end loop;
end $$;

-- =============================================================
-- 5) Post-conditions
-- =============================================================
do $$
declare
    remaining bigint;
    spine     bigint;
    orphans   bigint;
begin
    -- No FK may still point at another context (spine excluded).
    select count(*) into remaining
      from pg_constraint c
      join pg_class t on t.oid=c.conrelid  join pg_namespace n  on n.oid=t.relnamespace
      join pg_class cf on cf.oid=c.confrelid join pg_namespace nf on nf.oid=cf.relnamespace
     where c.contype='f' and n.nspname <> nf.nspname
       and n.nspname in ('identity','creator','campaign','workflow',
                         'attribution','finance','content','mapping')
       and not (nf.nspname='identity' and cf.relname in ('brands','users'));
    if remaining > 0 then
        raise exception 'Phase 5: % cross-context FK(s) still present', remaining;
    end if;

    -- The spine must survive: dropping it would remove brand validation everywhere.
    select count(*) into spine
      from pg_constraint c
      join pg_class t on t.oid=c.conrelid  join pg_namespace n  on n.oid=t.relnamespace
      join pg_class cf on cf.oid=c.confrelid join pg_namespace nf on nf.oid=cf.relnamespace
     where c.contype='f' and nf.nspname='identity' and cf.relname in ('brands','users')
       and n.nspname <> 'identity';
    if spine = 0 then
        raise exception 'Phase 5: tenancy-spine FKs were dropped; they must be kept';
    end if;

    -- Severing must not have created orphans.
    select (select count(*) from creator.orphaned_references)
         + (select count(*) from attribution.orphaned_references)
         + (select count(*) from finance.orphaned_references)
         + (select count(*) from content.orphaned_references)
      into orphans;
    if orphans > 0 then
        raise warning 'Phase 5: % orphaned cross-context reference(s) exist — investigate', orphans;
    end if;

    raise notice 'Cross-context FKs severed. Spine FKs kept (%). Orphan views report % row(s).',
                 spine, orphans;
end $$;
