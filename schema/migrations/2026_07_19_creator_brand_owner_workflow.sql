-- =============================================================
-- Migration: Creator + brand-owner workflow extensions
-- Date: 2026-07-19
-- Purpose:
--   1) Add workflow enums
--   2) Add workflow tables for tasks, approvals, payments, and timeline events
--   3) Add indexes and updated_at triggers where needed
-- Notes:
--   - Idempotent by design (safe to re-run)
--   - Requires existing users, creators, campaign_creators tables
-- =============================================================

create extension if not exists "pgcrypto";

-- ---- enums -------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_type where typname = 'workflow_actor') then
        create type workflow_actor as enum ('brand_owner', 'creator', 'system');
    end if;

    if not exists (select 1 from pg_type where typname = 'workflow_task_status') then
        create type workflow_task_status as enum ('todo', 'in_progress', 'blocked', 'submitted', 'approved', 'rejected', 'done');
    end if;

    if not exists (select 1 from pg_type where typname = 'approval_decision') then
        create type approval_decision as enum ('approved', 'changes_requested', 'rejected');
    end if;

    if not exists (select 1 from pg_type where typname = 'payout_status') then
        create type payout_status as enum ('draft', 'pending', 'scheduled', 'paid', 'failed', 'cancelled');
    end if;
end $$;

-- ---- tables ------------------------------------------------
create table if not exists creator_workflow_tasks (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_creator_id uuid not null references campaign_creators(id) on delete cascade,
    task_type           text not null default 'task',
    stage_key           pipeline_stage,
    title               text not null,
    description         text,
    assignee_actor      workflow_actor not null default 'brand_owner',
    assignee_creator_id uuid references creators(id) on delete set null,
    agreed_fee          numeric(12,2),
    tags                jsonb not null default '[]'::jsonb,
    status              workflow_task_status not null default 'todo',
    priority            text not null default 'medium',
    due_at              timestamptz,
    started_at          timestamptz,
    completed_at        timestamptz,
    metadata            jsonb not null default '{}'::jsonb,
    created_by_actor    workflow_actor not null default 'brand_owner',
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint creator_workflow_tasks_assignee_ck
        check (
            (assignee_actor = 'creator' and assignee_creator_id is not null)
            or assignee_actor in ('brand_owner', 'system')
        )
);

create table if not exists creator_workflow_approvals (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_creator_id uuid not null references campaign_creators(id) on delete cascade,
    review_round        integer not null default 1,
    submission_url      text,
    submission_notes    text,
    submitted_by_actor  workflow_actor not null default 'creator',
    submitted_at        timestamptz not null default now(),
    decision            approval_decision,
    decision_notes      text,
    decided_by_actor    workflow_actor,
    decided_at          timestamptz,
    metadata            jsonb not null default '{}'::jsonb,
    created_at          timestamptz not null default now(),
    constraint creator_workflow_approvals_decision_ck
        check (
            (decision is null and decided_at is null and decided_by_actor is null)
            or (decision is not null and decided_at is not null and decided_by_actor is not null)
        )
);

create table if not exists creator_workflow_payments (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_creator_id uuid not null references campaign_creators(id) on delete cascade,
    currency            text not null default 'USD',
    amount              numeric(12,2) not null,
    status              payout_status not null default 'draft',
    invoice_reference   text,
    payment_provider    text,
    provider_txn_id     text,
    notes               text,
    scheduled_at        timestamptz,
    paid_at             timestamptz,
    failed_at           timestamptz,
    metadata            jsonb not null default '{}'::jsonb,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table if not exists creator_workflow_events (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_creator_id uuid not null references campaign_creators(id) on delete cascade,
    actor               workflow_actor not null default 'system',
    actor_creator_id    uuid references creators(id) on delete set null,
    event_type          text not null,
    event_body          text,
    event_data          jsonb not null default '{}'::jsonb,
    created_at          timestamptz not null default now(),
    constraint creator_workflow_events_actor_ck
        check (
            (actor = 'creator' and actor_creator_id is not null)
            or actor in ('brand_owner', 'system')
        )
);

-- ---- indexes -----------------------------------------------
create index if not exists idx_cwt_user on creator_workflow_tasks(user_id);
create index if not exists idx_cwt_campaign_creator on creator_workflow_tasks(campaign_creator_id);
create index if not exists idx_cwt_task_type_stage on creator_workflow_tasks(task_type, stage_key, due_at);
create index if not exists idx_cwt_status_due on creator_workflow_tasks(status, due_at);

create index if not exists idx_cwa_user on creator_workflow_approvals(user_id);
create index if not exists idx_cwa_campaign_creator on creator_workflow_approvals(campaign_creator_id);
create index if not exists idx_cwa_submitted_at on creator_workflow_approvals(submitted_at);

create index if not exists idx_cwp_user on creator_workflow_payments(user_id);
create index if not exists idx_cwp_campaign_creator on creator_workflow_payments(campaign_creator_id);
create index if not exists idx_cwp_status_scheduled on creator_workflow_payments(status, scheduled_at);

create index if not exists idx_cwe_user on creator_workflow_events(user_id);
create index if not exists idx_cwe_campaign_creator on creator_workflow_events(campaign_creator_id);
create index if not exists idx_cwe_created_at on creator_workflow_events(created_at);

-- ---- trigger function --------------------------------------
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

-- ---- triggers ----------------------------------------------
do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_cwt_updated') then
        create trigger trg_cwt_updated
            before update on creator_workflow_tasks
            for each row execute function set_updated_at();
    end if;

    if not exists (select 1 from pg_trigger where tgname = 'trg_cwp_updated') then
        create trigger trg_cwp_updated
            before update on creator_workflow_payments
            for each row execute function set_updated_at();
    end if;
end $$;
