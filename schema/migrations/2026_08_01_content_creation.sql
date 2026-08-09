-- =============================================================
-- Migration: Content creation — campaign briefs + landing pages
-- Date: 2026-08-01
-- Purpose (docs/coupon-attribution-plan.md §8b):
--   Content Phase 1: campaign_briefs (brand-authored brief per campaign)
--   Content Phase 2: landing_templates (block model) + coupon personalization
--                    columns + landing_page_views (click/landing attribution)
--   Content Phase 3: personalization columns reused (blurb/embed/approval)
-- Notes:
--   - Idempotent by design (safe to re-run).
--   - Additive only. jsonb columns; text (not enum) status columns.
-- =============================================================

create extension if not exists "pgcrypto";

-- ---- 1) campaign_briefs (Content Phase 1) ----
create table if not exists campaign_briefs (
    id               uuid primary key default gen_random_uuid(),
    user_id          uuid not null references users(id) on delete cascade,
    campaign_id      uuid not null references campaigns(id) on delete cascade,
    content          jsonb not null default '{}'::jsonb,   -- { summary, goals, dos, donts, talkingPoints }
    assets           jsonb not null default '[]'::jsonb,   -- [ { label, url } ]
    hashtags         jsonb not null default '[]'::jsonb,   -- [ "#summer", ... ]
    disclosure_text  text,
    status           text not null default 'draft',        -- draft | published
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_campaign_briefs_campaign') then
        alter table campaign_briefs add constraint uq_campaign_briefs_campaign unique (campaign_id);
    end if;
end $$;

create index if not exists idx_campaign_briefs_user on campaign_briefs(user_id);

-- ---- 2) landing_templates (Content Phase 2) ----
create table if not exists landing_templates (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    campaign_id  uuid not null references campaigns(id) on delete cascade,
    public_slug  text not null,                        -- URL slug for /s/{public_slug}/{creator}
    name         text not null default 'Landing page',
    blocks       jsonb not null default '[]'::jsonb,   -- ordered [ { type, ... } ]
    theme        jsonb not null default '{}'::jsonb,
    status       text not null default 'draft',        -- draft | published
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_landing_templates_campaign') then
        alter table landing_templates add constraint uq_landing_templates_campaign unique (campaign_id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'uq_landing_templates_slug') then
        alter table landing_templates add constraint uq_landing_templates_slug unique (public_slug);
    end if;
end $$;

create index if not exists idx_landing_templates_user on landing_templates(user_id);

-- ---- 3) coupon personalization columns (Content Phase 2/3) ----
do $$
begin
    if not exists (select 1 from information_schema.columns where table_name='influencer_campaign_codes' and column_name='public_slug') then
        alter table influencer_campaign_codes add column public_slug text;
    end if;
    if not exists (select 1 from information_schema.columns where table_name='influencer_campaign_codes' and column_name='personal_blurb') then
        alter table influencer_campaign_codes add column personal_blurb text;
    end if;
    if not exists (select 1 from information_schema.columns where table_name='influencer_campaign_codes' and column_name='embed_url') then
        alter table influencer_campaign_codes add column embed_url text;
    end if;
    if not exists (select 1 from information_schema.columns where table_name='influencer_campaign_codes' and column_name='personalization_status') then
        alter table influencer_campaign_codes add column personalization_status text not null default 'none'; -- none | pending | approved | rejected
    end if;
end $$;

-- ---- 4) landing_page_views (Content Phase 2 attribution tie-in) ----
create table if not exists landing_page_views (
    id                uuid primary key default gen_random_uuid(),
    user_id           uuid not null references users(id) on delete cascade,
    campaign_code_id  uuid references influencer_campaign_codes(id) on delete cascade,
    referrer          text,
    user_agent        text,
    occurred_at       timestamptz not null default now(),
    created_at        timestamptz not null default now()
);

create index if not exists idx_lpv_user on landing_page_views(user_id);
create index if not exists idx_lpv_code on landing_page_views(campaign_code_id);
create index if not exists idx_lpv_occurred on landing_page_views(occurred_at);

-- ---- trigger function (shared) -----------------------------
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
    if not exists (select 1 from pg_trigger where tgname = 'trg_campaign_briefs_updated') then
        create trigger trg_campaign_briefs_updated before update on campaign_briefs
            for each row execute function set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_landing_templates_updated') then
        create trigger trg_landing_templates_updated before update on landing_templates
            for each row execute function set_updated_at();
    end if;
end $$;
