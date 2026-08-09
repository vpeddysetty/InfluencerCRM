-- =============================================================
-- Migration: Coupons + marketplace connections + commissions + payouts
-- Date: 2026-07-27
-- Purpose:
--   Phase 0 of the coupon/attribution/revenue plan (docs/coupon-attribution-plan.md).
--   1) Extend influencer_campaign_codes into a full coupon spine (discount,
--      commission, channel, external-marketplace sync fields).
--   2) Add marketplace_connections (a brand's connected external store instance).
--   3) Add influencer_commissions (accrued commission per attributed sale).
--   4) Add influencer_payouts (a batch settling commissions to one creator).
--   5) Add daily_attribution_stats (pre-aggregated rollup for the dashboard).
-- Notes:
--   - Idempotent by design (safe to re-run).
--   - Additive only: existing codes/attributions tables are extended, not replaced.
--   - Credentials on marketplace_connections are stored ENCRYPTED (envelope
--     encryption applied in the app layer); this column never holds plaintext.
-- =============================================================

create extension if not exists "pgcrypto";

-- ---- 1) extend influencer_campaign_codes into a coupon spine ----
-- Add the column type-only here; the FK to marketplace_connections is added in a
-- deferred block below (that table is created later in this migration).
alter table influencer_campaign_codes
    add column if not exists marketplace_connection_id uuid;

do $$
begin
    -- discount definition
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'discount_type') then
        alter table influencer_campaign_codes add column discount_type text;         -- percent | fixed | free_shipping | bogo
    end if;
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'discount_value') then
        alter table influencer_campaign_codes add column discount_value numeric(12,2);
    end if;
    -- commission the influencer earns on attributed sales
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'commission_type') then
        alter table influencer_campaign_codes add column commission_type text;        -- percent | fixed
    end if;
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'commission_value') then
        alter table influencer_campaign_codes add column commission_value numeric(12,2);
    end if;
    -- attribution channel + affiliate link
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'channel') then
        alter table influencer_campaign_codes add column channel text;                -- instagram | tiktok | youtube | email | ...
    end if;
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'ref_slug') then
        alter table influencer_campaign_codes add column ref_slug text;               -- affiliate-link ref token
    end if;
    -- external marketplace sync
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'external_coupon_id') then
        alter table influencer_campaign_codes add column external_coupon_id text;
    end if;
    if not exists (select 1 from information_schema.columns where table_name = 'influencer_campaign_codes' and column_name = 'sync_status') then
        alter table influencer_campaign_codes add column sync_status text not null default 'local';  -- local | synced | sync_failed
    end if;
end $$;

-- ---- 2) marketplace_connections ----
create table if not exists marketplace_connections (
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid not null references users(id) on delete cascade,
    provider_key          text not null,                          -- "shopify", "mock", ...
    display_name          text not null,
    status                text not null default 'connected',      -- connected | disconnected | error
    credentials_encrypted text,                                   -- envelope-encrypted blob; NEVER plaintext
    external_account_ref  text,                                   -- vendor account/shop handle
    sync_cursor           timestamptz,                            -- last order-poll watermark
    metadata              jsonb not null default '{}'::jsonb,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

create index if not exists idx_mc_user on marketplace_connections(user_id);
create index if not exists idx_mc_provider on marketplace_connections(user_id, provider_key);

-- deferred FK from codes -> connections (both tables now exist)
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_icc_marketplace_connection') then
        alter table influencer_campaign_codes
            add constraint fk_icc_marketplace_connection
            foreign key (marketplace_connection_id) references marketplace_connections(id) on delete set null;
    end if;
end $$;

-- ---- 3) influencer_commissions (accrued per attributed sale) ----
create table if not exists influencer_commissions (
    id                uuid primary key default gen_random_uuid(),
    user_id           uuid not null references users(id) on delete cascade,
    attribution_id    uuid references influencer_sale_attributions(id) on delete set null,
    creator_id        uuid not null references creators(id) on delete cascade,
    campaign_id       uuid references campaigns(id) on delete set null,
    gross_sale        numeric(12,2) not null default 0,
    commission_amount numeric(12,2) not null default 0,
    currency          text not null default 'USD',
    status            text not null default 'pending',            -- pending | approved | paid | clawed_back | void
    approved_at       timestamptz,
    payout_id         uuid,                                       -- FK added after payouts table exists
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index if not exists idx_ic_user on influencer_commissions(user_id);
create index if not exists idx_ic_creator on influencer_commissions(creator_id);
create index if not exists idx_ic_status on influencer_commissions(user_id, status);
create index if not exists idx_ic_attribution on influencer_commissions(attribution_id);
create index if not exists idx_ic_payout on influencer_commissions(payout_id);

-- ---- 4) influencer_payouts (batch settlement to one creator) ----
create table if not exists influencer_payouts (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    creator_id    uuid not null references creators(id) on delete cascade,
    period_start  timestamptz,
    period_end    timestamptz,
    total_amount  numeric(12,2) not null default 0,
    currency      text not null default 'USD',
    method        text,                                           -- manual | stripe | paypal | wise ...
    provider_key  text,
    provider_ref  text,                                           -- external payout reference
    status        text not null default 'draft',                 -- draft | processing | paid | failed
    notes         text,
    created_at    timestamptz not null default now(),
    paid_at       timestamptz,
    updated_at    timestamptz not null default now()
);

create index if not exists idx_ip_user on influencer_payouts(user_id);
create index if not exists idx_ip_creator on influencer_payouts(creator_id);
create index if not exists idx_ip_status on influencer_payouts(user_id, status);

-- deferred FK from commissions -> payouts (both tables now exist)
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_ic_payout') then
        alter table influencer_commissions
            add constraint fk_ic_payout
            foreign key (payout_id) references influencer_payouts(id) on delete set null;
    end if;
end $$;

-- ---- 5) daily_attribution_stats (dashboard rollup) ----
create table if not exists daily_attribution_stats (
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid not null references users(id) on delete cascade,
    day          date not null,
    creator_id   uuid references creators(id) on delete cascade,
    campaign_id  uuid references campaigns(id) on delete cascade,
    channel      text,
    clicks       integer not null default 0,
    orders       integer not null default 0,
    gross_sales  numeric(12,2) not null default 0,
    discounts    numeric(12,2) not null default 0,
    commission   numeric(12,2) not null default 0,
    refunds      numeric(12,2) not null default 0,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

-- one rollup row per (user, day, creator, campaign, channel) grain
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_das_grain') then
        alter table daily_attribution_stats
            add constraint uq_das_grain unique (user_id, day, creator_id, campaign_id, channel);
    end if;
end $$;

create index if not exists idx_das_user_day on daily_attribution_stats(user_id, day);
create index if not exists idx_das_creator on daily_attribution_stats(creator_id);
create index if not exists idx_das_campaign on daily_attribution_stats(campaign_id);

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
    if not exists (select 1 from pg_trigger where tgname = 'trg_mc_updated') then
        create trigger trg_mc_updated before update on marketplace_connections
            for each row execute function set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_ic_updated') then
        create trigger trg_ic_updated before update on influencer_commissions
            for each row execute function set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_ip_updated') then
        create trigger trg_ip_updated before update on influencer_payouts
            for each row execute function set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_das_updated') then
        create trigger trg_das_updated before update on daily_attribution_stats
            for each row execute function set_updated_at();
    end if;
end $$;
