-- =============================================================
-- Migration: Tie existing campaigns/work items to workflow setup
-- Date: 2026-07-21
-- Purpose:
--   1) Normalize campaign_type on campaigns
--   2) Ensure campaign_type_workflow_stages exist per user+campaign_type
--   3) Reconcile workflow-item tasks to active workflow stages
-- =============================================================

create extension if not exists "pgcrypto";

-- 1) Normalize campaign type values so lookups are deterministic.
update campaigns
set campaign_type = lower(btrim(campaign_type))
where campaign_type is not null
  and campaign_type <> lower(btrim(campaign_type));

update campaigns
set campaign_type = 'paid'
where campaign_type is null or btrim(campaign_type) = '';

-- 2) For any user+campaign_type missing workflow rows, seed default stage definitions.
with user_types as (
    select distinct c.user_id, c.campaign_type
    from campaigns c
),
default_stage_defs(stage_key, stage_label, position) as (
    values
      ('outreach'::pipeline_stage, 'Outreach', 0),
      ('agreed'::pipeline_stage, 'Agreed', 1),
      ('shipped'::pipeline_stage, 'Shipped', 2),
      ('posted'::pipeline_stage, 'Posted', 3),
      ('paid'::pipeline_stage, 'Paid', 4)
)
insert into campaign_type_workflow_stages (user_id, campaign_type, stage_key, stage_label, position, is_active)
select ut.user_id,
       ut.campaign_type,
       d.stage_key,
       d.stage_label,
       d.position,
       true
from user_types ut
cross join default_stage_defs d
where not exists (
    select 1
    from campaign_type_workflow_stages cws
    where cws.user_id = ut.user_id
      and cws.campaign_type = ut.campaign_type
      and cws.stage_key = d.stage_key
);

-- 3) If a user+campaign_type has zero active stages, activate the earliest stage.
with first_stage as (
    select cws.user_id,
           cws.campaign_type,
           cws.id,
           row_number() over (partition by cws.user_id, cws.campaign_type order by cws.position asc, cws.created_at asc, cws.id asc) as rn
    from campaign_type_workflow_stages cws
),
inactive_types as (
    select cws.user_id, cws.campaign_type
    from campaign_type_workflow_stages cws
    group by cws.user_id, cws.campaign_type
    having bool_or(cws.is_active) = false
)
update campaign_type_workflow_stages cws
set is_active = true
from first_stage fs
join inactive_types it
  on it.user_id = fs.user_id
 and it.campaign_type = fs.campaign_type
where cws.id = fs.id
  and fs.rn = 1;

-- 4) Tie existing workflow-item tasks to active configured stages.
--    If a workflow item task stage is not active for that campaign type,
--    move it to the first active stage by workflow position.
with first_active_stage as (
    select cws.user_id,
           cws.campaign_type,
           cws.stage_key,
           row_number() over (partition by cws.user_id, cws.campaign_type order by cws.position asc, cws.created_at asc, cws.id asc) as rn
    from campaign_type_workflow_stages cws
    where cws.is_active = true
),
invalid_work_items as (
  select cwt.id,
           fa.stage_key as fallback_stage
  from creator_workflow_tasks cwt
  join campaign_creators cc
    on cc.id = cwt.campaign_creator_id
    join campaigns c
      on c.id = cc.campaign_id
    join first_active_stage fa
    on fa.user_id = cwt.user_id
     and fa.campaign_type = c.campaign_type
     and fa.rn = 1
    left join campaign_type_workflow_stages cws
    on cws.user_id = cwt.user_id
     and cws.campaign_type = c.campaign_type
   and cws.stage_key = cwt.stage_key
     and cws.is_active = true
  where cwt.task_type = 'workflow_item'
    and cws.id is null
)
update creator_workflow_tasks cwt
set stage_key = iw.fallback_stage,
  updated_at = now()
from invalid_work_items iw
where cwt.id = iw.id;
