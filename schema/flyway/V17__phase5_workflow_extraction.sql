-- =============================================================
-- Migration: Workflow extraction prerequisites  (DDD Phase 5, context #1)
-- Date: 2026-08-02
-- Purpose:
--   Sever the Workflow context's cross-context foreign keys so it can run against
--   its own database.
--
--   Workflow is the pilot extraction (see docs/EXTRACTION-RUNBOOK.md): three
--   tables, no money, no inbound ports. It is the cheapest place to discover what
--   the runbook got wrong.
--
-- What this changes:
--   workflow_cards holds FKs to campaigns and creators — tables owned by other
--   contexts. A separate service cannot enforce those, because the referenced rows
--   live in a database it has no access to. They become ID-only references, which
--   is the rule the migration plan set out in section 5.2.
--
--   FKs *within* Workflow (cards -> boards, stages -> boards) are kept: they are
--   internal to the aggregate and remain enforceable after extraction.
--
--   FKs to identity.brands are also kept for now. Every context validates a brand,
--   and the runbook grants each service SELECT on the tenancy spine precisely so
--   that check stays a local query rather than a network hop. They are dropped only
--   if Identity moves to a physically separate database.
--
-- Why now and not at cutover:
--   Dropping an FK is instant; discovering at cutover that orphan rows exist is not.
--   Doing it first means the referential gap is visible in the monolith, where it is
--   cheap to fix, rather than in a freshly split service.
--
-- Replacement safety net:
--   A dropped FK removes the database's guarantee. Two things replace it:
--     1) the application already validates campaign/creator ids before writing a card
--     2) an orphan-detection view, so the gap is observable rather than silent
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- =============================================================
-- 1) Report any rows that would become orphans
-- =============================================================
-- Run before dropping, so a pre-existing integrity problem is not silently
-- inherited by the new service.
do $$
declare
    orphan_campaigns bigint;
    orphan_creators  bigint;
begin
    select count(*) into orphan_campaigns
      from workflow.workflow_cards c
     where c.campaign_id is not null
       and not exists (select 1 from campaign.campaigns x where x.id = c.campaign_id);

    select count(*) into orphan_creators
      from workflow.workflow_cards c
     where c.creator_id is not null
       and not exists (select 1 from creator.creators x where x.id = c.creator_id);

    if orphan_campaigns > 0 or orphan_creators > 0 then
        raise warning
            'workflow_cards already has % row(s) referencing a missing campaign and % referencing '
            'a missing creator. Dropping the FKs will not create this, but it will stop the database '
            'catching more of it — see the orphan view created below.',
            orphan_campaigns, orphan_creators;
    else
        raise notice 'workflow_cards has no orphan campaign/creator references.';
    end if;
end $$;

-- =============================================================
-- 2) Drop the cross-context foreign keys
-- =============================================================
alter table workflow.workflow_cards
    drop constraint if exists workflow_cards_campaign_id_fkey;

alter table workflow.workflow_cards
    drop constraint if exists workflow_cards_creator_id_fkey;

comment on column workflow.workflow_cards.campaign_id is
    'ID-only reference to campaign.campaigns. No FK: the Campaign context may live in a separate '
    'database. Validated by the application before write, and monitored by workflow.orphaned_cards.';

comment on column workflow.workflow_cards.creator_id is
    'ID-only reference to creator.creators. No FK, for the same reason as campaign_id.';

-- =============================================================
-- 3) Make the referential gap observable
-- =============================================================
-- Dropping an FK trades an enforced guarantee for an unenforced assumption. This
-- view is what keeps the assumption honest: it should always return zero rows, and
-- it can be alerted on.
create or replace view workflow.orphaned_cards as
select c.id            as card_id,
       c.brand_id,
       c.campaign_id,
       c.creator_id,
       case when c.campaign_id is not null
             and not exists (select 1 from campaign.campaigns x where x.id = c.campaign_id)
            then true else false end as campaign_missing,
       case when c.creator_id is not null
             and not exists (select 1 from creator.creators x where x.id = c.creator_id)
            then true else false end as creator_missing
  from workflow.workflow_cards c
 where (c.campaign_id is not null
        and not exists (select 1 from campaign.campaigns x where x.id = c.campaign_id))
    or (c.creator_id is not null
        and not exists (select 1 from creator.creators x where x.id = c.creator_id));

comment on view workflow.orphaned_cards is
    'Cards referencing a campaign or creator that no longer exists. Replaces the foreign keys '
    'dropped for extraction: should always be empty, and is safe to alert on.';

-- The Workflow service reads its own monitoring view; it needs the cross-context
-- SELECT the view depends on, which svc_workflow already has on identity but not
-- on campaign/creator. Grant read-only so the check works without widening writes.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_workflow') then
        execute 'grant select on workflow.orphaned_cards to svc_workflow';
        execute 'grant usage on schema campaign, creator to svc_workflow';
        execute 'grant select on campaign.campaigns, creator.creators to svc_workflow';
    end if;
end $$;

-- =============================================================
-- 4) Post-conditions
-- =============================================================
do $$
declare
    remaining bigint;
    internal  bigint;
begin
    -- The cross-context FKs must be gone.
    select count(*) into remaining
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
     where n.nspname = 'workflow'
       and c.contype = 'f'
       and c.conname in ('workflow_cards_campaign_id_fkey', 'workflow_cards_creator_id_fkey');
    if remaining > 0 then
        raise exception 'Phase 5: % cross-context FK(s) still present on workflow_cards', remaining;
    end if;

    -- FKs internal to the aggregate must remain: they stay enforceable after extraction.
    select count(*) into internal
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
     where n.nspname = 'workflow'
       and c.contype = 'f'
       and c.conname in ('workflow_cards_board_id_fkey',
                         'workflow_cards_stage_id_fkey',
                         'workflow_board_stages_board_id_fkey');
    if internal <> 3 then
        raise exception 'Phase 5: expected 3 intra-Workflow FKs to survive, found %', internal;
    end if;

    raise notice 'Workflow extraction prerequisites OK: cross-context FKs dropped, '
                 'intra-aggregate FKs kept, orphan view in place.';
end $$;
