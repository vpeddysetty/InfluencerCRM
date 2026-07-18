-- =============================================================
-- Migration: Custom attributes + review field sync
-- Date: 2026-07-17
-- Purpose:
--   1) Add custom_attributes JSONB to core entities
--   2) Ensure content review fields exist in campaign_creators
--   3) Ensure pgvector extension is available
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

create extension if not exists vector;

alter table users
    add column if not exists custom_attributes jsonb not null default '{}'::jsonb;

alter table creators
    add column if not exists custom_attributes jsonb not null default '{}'::jsonb;

alter table campaigns
    add column if not exists custom_attributes jsonb not null default '{}'::jsonb;

alter table campaign_creators
    add column if not exists custom_attributes jsonb not null default '{}'::jsonb;

alter table campaign_creators
    add column if not exists content_review_status content_review_status not null default 'not_requested';

alter table campaign_creators
    add column if not exists content_review_requested_at timestamptz;

alter table campaign_creators
    add column if not exists content_review_completed_at timestamptz;

alter table campaign_creators
    add column if not exists content_review_notes text;

alter table campaign_creators
    add column if not exists content_reviewed_by text;
