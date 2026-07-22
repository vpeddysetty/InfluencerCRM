-- =============================================================
-- Migration: Remove workflow swimlane persistence
-- Date: 2026-07-21
-- Purpose:
--   1) Remove workflow_swimlanes table
--   2) Remove campaign_creators.swimlane_name column
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

drop trigger if exists trg_workflow_swimlanes_updated on workflow_swimlanes;
drop index if exists idx_workflow_swimlanes_user_position;

alter table if exists campaign_creators
    drop column if exists swimlane_name;

drop table if exists workflow_swimlanes;