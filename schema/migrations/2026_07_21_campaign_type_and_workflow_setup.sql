-- =============================================================
-- Migration: Campaign type + campaign type workflow setup
-- Date: 2026-07-21
-- Purpose:
--   1) Ensure campaigns.campaign_type exists and is populated
--   2) Add campaign type workflow stage configuration table
-- =============================================================

create extension if not exists "pgcrypto";

alter table campaigns
    add column if not exists campaign_type text;

update campaigns
set campaign_type = 'paid'
where campaign_type is null or btrim(campaign_type) = '';

alter table campaigns
    alter column campaign_type set default 'paid';

alter table campaigns
    alter column campaign_type set not null;

create table if not exists campaign_type_workflow_stages (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    campaign_type text not null,
    stage_key     pipeline_stage not null,
    stage_label   text not null,
    position      integer not null default 0,
    is_active     boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    unique (user_id, campaign_type, stage_key)
);

insert into campaign_type_workflow_stages (user_id, campaign_type, stage_key, stage_label, position, is_active)
select u.id,
       t.campaign_type,
       s.stage_key::pipeline_stage,
       s.stage_label,
       s.position,
       true
from users u
cross join (
    select distinct campaign_type from campaigns
    union
    select 'paid'
) t
cross join (
    values
        ('outreach', 'Outreach', 0),
        ('agreed', 'Agreed', 1),
        ('shipped', 'Shipped', 2),
        ('posted', 'Posted', 3),
        ('paid', 'Paid', 4)
) as s(stage_key, stage_label, position)
where not exists (
    select 1
    from campaign_type_workflow_stages c
    where c.user_id = u.id
      and c.campaign_type = t.campaign_type
      and c.stage_key = s.stage_key::pipeline_stage
);

create index if not exists idx_campaign_type_workflow_stages_user_type
    on campaign_type_workflow_stages(user_id, campaign_type, position);

create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_campaign_type_workflow_stages_updated') then
        create trigger trg_campaign_type_workflow_stages_updated
            before update on campaign_type_workflow_stages
            for each row execute function set_updated_at();
    end if;
end $$;
