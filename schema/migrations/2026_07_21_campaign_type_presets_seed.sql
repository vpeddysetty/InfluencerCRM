-- =============================================================
-- Migration: Seed preset campaign types for workflow setup
-- Date: 2026-07-21
-- Purpose:
--   Ensure workflow setup presets exist for requested campaign types
-- =============================================================

create extension if not exists "pgcrypto";

with preset_types(campaign_type) as (
    values
      ('product seeding'),
      ('sponsored content'),
      ('gifting'),
      ('affiliate campaigns'),
      ('brand ambassador programs')
),
stage_defs(stage_key, stage_label, position) as (
    values
      ('outreach', 'Outreach', 0),
      ('agreed', 'Agreed', 1),
      ('shipped', 'Shipped', 2),
      ('posted', 'Posted', 3),
      ('paid', 'Paid', 4)
)
insert into campaign_type_workflow_stages (user_id, campaign_type, stage_key, stage_label, position, is_active)
select u.id,
       p.campaign_type,
       s.stage_key::pipeline_stage,
       s.stage_label,
       s.position,
       true
from users u
cross join preset_types p
cross join stage_defs s
where not exists (
    select 1
    from campaign_type_workflow_stages c
    where c.user_id = u.id
      and c.campaign_type = p.campaign_type
      and c.stage_key = s.stage_key::pipeline_stage
);
