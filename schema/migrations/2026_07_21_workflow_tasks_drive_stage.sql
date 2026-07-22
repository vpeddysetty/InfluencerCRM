-- =============================================================
-- Migration: Workflow tasks own stage state
-- Date: 2026-07-21
-- Purpose:
--   1) Move workflow stage ownership from campaign_creators to creator_workflow_tasks
--   2) Backfill one workflow_item task per campaign_creator where missing
--   3) Remove campaign_creators.stage
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

alter table creator_workflow_tasks
    add column if not exists task_type text not null default 'task';

alter table creator_workflow_tasks
    add column if not exists stage_key pipeline_stage;

alter table creator_workflow_tasks
    add column if not exists agreed_fee numeric(12,2);

alter table creator_workflow_tasks
    add column if not exists tags jsonb not null default '[]'::jsonb;

create index if not exists idx_cwt_task_type_stage
    on creator_workflow_tasks(task_type, stage_key, due_at);

create unique index if not exists idx_cwt_one_workflow_item_per_campaign_creator
    on creator_workflow_tasks(campaign_creator_id)
    where task_type = 'workflow_item';

do $$
begin
    if exists (
        select 1
        from information_schema.columns
        where table_schema = 'public'
          and table_name = 'campaign_creators'
          and column_name = 'stage'
    ) then
        insert into creator_workflow_tasks (
            user_id,
            campaign_creator_id,
            task_type,
            stage_key,
            title,
            description,
            assignee_actor,
            agreed_fee,
            tags,
            status,
            priority,
            due_at,
            metadata,
            created_by_actor,
            created_at,
            updated_at
        )
        select cc.user_id,
               cc.id,
               'workflow_item',
               cc.stage,
               'Workflow item',
               cc.notes,
               'brand_owner'::workflow_actor,
               cc.agreed_fee,
               coalesce(cc.tags, '[]'::jsonb),
               'todo'::workflow_task_status,
               'medium',
               cc.content_due_at,
               '{}'::jsonb,
               'brand_owner'::workflow_actor,
               cc.created_at,
               cc.updated_at
        from campaign_creators cc
        where not exists (
            select 1
            from creator_workflow_tasks cwt
            where cwt.campaign_creator_id = cc.id
              and cwt.task_type = 'workflow_item'
        );

        alter table campaign_creators
            drop column if exists stage;
    end if;
end $$;

with first_active_stage as (
    select cws.user_id,
           cws.campaign_type,
           cws.stage_key,
           row_number() over (partition by cws.user_id, cws.campaign_type order by cws.position asc, cws.created_at asc, cws.id asc) as rn
    from campaign_type_workflow_stages cws
    where cws.is_active = true
),
missing_stage as (
    select cwt.id,
           fa.stage_key
    from creator_workflow_tasks cwt
    join campaign_creators cc on cc.id = cwt.campaign_creator_id
    join campaigns c on c.id = cc.campaign_id
    join first_active_stage fa
      on fa.user_id = cwt.user_id
     and fa.campaign_type = c.campaign_type
     and fa.rn = 1
    where cwt.task_type = 'workflow_item'
      and cwt.stage_key is null
)
update creator_workflow_tasks cwt
set stage_key = ms.stage_key,
    updated_at = now()
from missing_stage ms
where cwt.id = ms.id;