-- =============================================================
-- Migration: Workflow swimlanes + assignment lane/notes fields
-- Date: 2026-07-21
-- Purpose:
--   1) Track notes and tags for campaign_creators work items
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

create extension if not exists "pgcrypto";

alter table campaign_creators
    add column if not exists notes text;

alter table campaign_creators
    add column if not exists tags jsonb not null default '[]'::jsonb;
