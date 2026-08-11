-- =============================================================
-- Migration: Brand <-> creator collaboration (share links + review notes)
-- Date: 2026-08-02
-- Purpose (docs/coupon-attribution-plan.md §8c):
--   Phase 1: share_tokens (tokenized, scoped, no-login access to brief/landing)
--   Phase 2: content_review_notes (creator notes on a shared draft)
--   Phase 3: campaigns.content_workflow_mode (collaborative | standalone)
-- Notes:
--   - Idempotent by design (safe to re-run). Additive only.
--   - text (not enum) status/scope columns; jsonb via @JdbcTypeCode in entities.
-- =============================================================

create extension if not exists "pgcrypto";

-- ---- 1) share_tokens (Phase 1) ----
create table if not exists public.share_tokens (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    campaign_id  uuid not null references campaigns(id) on delete cascade,
    creator_id   uuid references creators(id) on delete cascade,   -- null = brief-only share
    token        text not null,
    scope        text not null default 'brief_view',  -- brief_view | landing_review | landing_edit
    expires_at   timestamptz,
    revoked      boolean not null default false,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_share_tokens_token') then
        alter table share_tokens add constraint uq_share_tokens_token unique (token);
    end if;
end $$;

create index if not exists idx_share_tokens_user on share_tokens(user_id);
create index if not exists idx_share_tokens_campaign on share_tokens(campaign_id);

-- ---- 2) content_review_notes (Phase 2) ----
create table if not exists public.content_review_notes (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references users(id) on delete cascade,
    campaign_id     uuid not null references campaigns(id) on delete cascade,
    creator_id      uuid references creators(id) on delete cascade,
    share_token_id  uuid references share_tokens(id) on delete set null,
    block_ref       text,                                 -- optional per-block anchor (future)
    author          text not null default 'creator',      -- creator | brand
    body            text not null,
    status          text not null default 'open',          -- open | resolved
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index if not exists idx_crn_user on content_review_notes(user_id);
create index if not exists idx_crn_campaign on content_review_notes(campaign_id);
create index if not exists idx_crn_token on content_review_notes(share_token_id);

-- ---- 3) campaigns.content_workflow_mode (Phase 3) ----
do $$
begin
    if not exists (select 1 from information_schema.columns where table_name='campaigns' and column_name='content_workflow_mode') then
        alter table campaigns add column content_workflow_mode text not null default 'standalone'; -- collaborative | standalone
    end if;
end $$;

-- ---- trigger function (shared) -----------------------------
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_share_tokens_updated') then
        create trigger trg_share_tokens_updated before update on share_tokens
            for each row execute function set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_crn_updated') then
        create trigger trg_crn_updated before update on content_review_notes
            for each row execute function set_updated_at();
    end if;
end $$;
