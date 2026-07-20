-- =============================================================
-- Migration: Influencer campaign codes + sale attribution tracking
-- Date: 2026-07-19
-- Purpose:
--   1) Add attribution enums
--   2) Add influencer_campaign_codes table
--   3) Add influencer_sale_attributions table
--   4) Add indexes + updated_at triggers
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

create extension if not exists "pgcrypto";

-- ---- enums -------------------------------------------------
do $$
begin
    if not exists (select 1 from pg_type where typname = 'attribution_platform') then
        create type attribution_platform as enum ('instagram', 'tiktok', 'youtube', 'shopify', 'amazon', 'woocommerce', 'direct', 'other');
    end if;

    if not exists (select 1 from pg_type where typname = 'attribution_status') then
        create type attribution_status as enum ('pending', 'attributed', 'refunded', 'cancelled');
    end if;
end $$;

-- ---- tables ------------------------------------------------
create table if not exists influencer_campaign_codes (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_id         uuid not null references campaigns(id) on delete cascade,
    creator_id          uuid not null references creators(id) on delete cascade,
    campaign_creator_id uuid references campaign_creators(id) on delete set null,
    code                text not null,
    code_type           text not null default 'discount',
    landing_url         text,
    starts_at           timestamptz,
    ends_at             timestamptz,
    is_active           boolean not null default true,
    metadata            jsonb not null default '{}'::jsonb,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

create table if not exists influencer_sale_attributions (
    id                   uuid primary key default gen_random_uuid(),
    user_id              uuid not null references users(id) on delete cascade,
    campaign_code_id     uuid not null references influencer_campaign_codes(id) on delete cascade,
    campaign_id          uuid not null references campaigns(id) on delete cascade,
    creator_id           uuid not null references creators(id) on delete cascade,
    campaign_creator_id  uuid references campaign_creators(id) on delete set null,
    platform             attribution_platform not null default 'direct',
    status               attribution_status not null default 'pending',
    order_id             text not null,
    order_line_id        text,
    customer_external_id text,
    sale_amount          numeric(12,2) not null,
    discount_amount      numeric(12,2) not null default 0,
    net_amount           numeric(12,2),
    commission_amount    numeric(12,2),
    currency             text not null default 'USD',
    occurred_at          timestamptz not null default now(),
    tracked_at           timestamptz not null default now(),
    raw_payload          jsonb not null default '{}'::jsonb,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

-- ---- constraints -------------------------------------------
do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'uq_influencer_campaign_codes_user_code'
    ) then
        alter table influencer_campaign_codes
            add constraint uq_influencer_campaign_codes_user_code unique (user_id, code);
    end if;
end $$;

-- ---- indexes -----------------------------------------------
create index if not exists idx_icc_user on influencer_campaign_codes(user_id);
create index if not exists idx_icc_campaign_creator on influencer_campaign_codes(campaign_id, creator_id);
create index if not exists idx_icc_code on influencer_campaign_codes(code);

create index if not exists idx_isa_user on influencer_sale_attributions(user_id);
create index if not exists idx_isa_code on influencer_sale_attributions(campaign_code_id);
create index if not exists idx_isa_campaign_creator on influencer_sale_attributions(campaign_creator_id);
create index if not exists idx_isa_platform_status on influencer_sale_attributions(platform, status);
create index if not exists idx_isa_occurred_at on influencer_sale_attributions(occurred_at);

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
    if not exists (select 1 from pg_trigger where tgname = 'trg_icc_updated') then
        create trigger trg_icc_updated
            before update on influencer_campaign_codes
            for each row execute function set_updated_at();
    end if;

    if not exists (select 1 from pg_trigger where tgname = 'trg_isa_updated') then
        create trigger trg_isa_updated
            before update on influencer_sale_attributions
            for each row execute function set_updated_at();
    end if;
end $$;
