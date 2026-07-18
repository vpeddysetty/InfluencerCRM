-- =============================================================
-- Influencer CRM — Phase 1 schema (PostgreSQL)
-- Single-tenant-per-account model: every table hangs off `users`.
-- Target: Postgres 13+
-- =============================================================

-- ---- extensions --------------------------------------------
create extension if not exists "pgcrypto";   -- for gen_random_uuid()
create extension if not exists "citext";     -- for case-insensitive email
create extension if not exists vector;        -- for pgvector embeddings

-- ---- enums -------------------------------------------------
create type user_role       as enum ('owner', 'marketer');
create type platform_type   as enum ('instagram', 'tiktok', 'youtube', 'other');
create type campaign_status as enum ('draft', 'active', 'completed', 'archived');
create type pipeline_stage  as enum ('outreach', 'agreed', 'shipped', 'posted', 'paid');
create type interaction_type as enum ('note', 'email', 'dm');
create type content_review_status as enum ('not_requested', 'requested', 'in_review', 'approved', 'rejected');

-- =============================================================
-- users  (the brand owner / solo marketer who signs up)
-- =============================================================
create table users (
    id            uuid primary key default gen_random_uuid(),
    email         citext not null unique,           -- case-insensitive; see note below
    password_hash text   not null,
    brand_name    text,
    custom_attributes jsonb not null default '{}'::jsonb,
    role          user_role   not null default 'owner',
    plan          text        not null default 'free',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);
-- Note: email uses `citext` (case-insensitive). To avoid the extension,
-- switch it to `text` and store lower-cased values instead.

-- =============================================================
-- creators  (the influencer list — owned by a user)
-- =============================================================
create table creators (
    id                      uuid primary key default gen_random_uuid(),
    user_id                 uuid not null references users(id) on delete cascade,
    import_batch_id         uuid,                              -- fk added after import_batches exists
    handle                  text not null,
    name                    text,
    email                   text,
    platform                platform_type not null default 'instagram',
    follower_count          integer,
    engagement_rate         numeric(5,2),                      -- e.g. 5.20 (%)
    tags                    text[] not null default '{}',
    notes                   text,
    status                  text not null default 'active',
    country                 text,
    city                    text,
    timezone                text,
    languages               text[] not null default '{}',
    niche                   text,
    content_categories      text[] not null default '{}',
    audience_demographics   jsonb not null default '{}'::jsonb,
    audience_size_estimate  bigint,
    average_views           bigint,
    last_active_at          timestamptz,
    source                  text not null default 'manual',
    brand_safety_score      numeric(5,2),
    safety_notes            text,
    preferred_rate          numeric(12,2),
    minimum_fee             numeric(12,2),
    currency                text not null default 'USD',
    custom_attributes       jsonb not null default '{}'::jsonb,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    -- a user can't have the same handle twice on the same platform
    unique (user_id, platform, handle)
);

-- =============================================================
-- campaigns  (owned by a user)
-- =============================================================
create table campaigns (
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid not null references users(id) on delete cascade,
    name                  text not null,
    goal                  text,
    product               text,
    budget                numeric(12,2),
    start_date            date,
    end_date              date,
    status                campaign_status not null default 'draft',
    campaign_type         text not null default 'paid',
    objective             text,
    target_audience       text,
    market_region         text,
    geo_targeting         text,
    deliverables_required text[] not null default '{}',
    kpi_target            text,
    currency              text not null default 'USD',
    priority              text not null default 'medium',
    brief_url             text,
    brief_notes           text,
    content_guidelines     text,
    campaign_owner        text,
    custom_attributes     jsonb not null default '{}'::jsonb,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

-- =============================================================
-- import_batches  (one row per uploaded sheet; enables undo/remap)
-- =============================================================
create table import_batches (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references users(id) on delete cascade,
    source_filename text not null,
    column_mapping  jsonb not null default '{}',       -- maps sheet columns -> fields
    row_count       integer not null default 0,
    created_at      timestamptz not null default now()
);

-- deferred fk: creators.import_batch_id -> import_batches.id
alter table creators
    add constraint creators_import_batch_fk
    foreign key (import_batch_id) references import_batches(id) on delete set null;

-- =============================================================
-- campaign_creators  (the join / pipeline row)
-- One row = one creator's participation in one campaign.
-- =============================================================
create table campaign_creators (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_id         uuid not null references campaigns(id) on delete cascade,
    creator_id          uuid not null references creators(id)  on delete cascade,
    import_batch_id     uuid references import_batches(id) on delete set null,  -- null = added manually
    stage               pipeline_stage not null default 'outreach',
    discount_code       text,
    link                text,
    agreed_fee          numeric(12,2),
    post_url            text,
    outreach_status     text not null default 'new',
    contract_status     text not null default 'not_sent',
    deliverable_status  text not null default 'pending',
    payment_status      text not null default 'pending',
    next_follow_up_at   timestamptz,
    last_contacted_at   timestamptz,
    contract_sent_at    timestamptz,
    contract_signed_at  timestamptz,
    content_due_at      timestamptz,
    content_review_status content_review_status not null default 'not_requested',
    content_review_requested_at timestamptz,
    content_review_completed_at timestamptz,
    content_review_notes text,
    content_reviewed_by  text,
    content_submitted_at timestamptz,
    content_approved_at  timestamptz,
    posted_at           timestamptz,
    paid_at             timestamptz,
    fee_currency        text not null default 'USD',
    payment_amount      numeric(12,2),
    performance_metrics jsonb not null default '{}'::jsonb,
    custom_attributes   jsonb not null default '{}'::jsonb,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    -- a creator appears at most once per campaign
    unique (campaign_id, creator_id)
);

-- =============================================================
-- interactions  (relationship memory: notes, emails, dms)
-- =============================================================
create table interactions (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    creator_id uuid not null references creators(id) on delete cascade,
    type       interaction_type not null default 'note',
    body       text not null,
    created_at timestamptz not null default now()
);

-- =============================================================
-- tenant indexes  (every list view filters by user_id)
-- =============================================================
create index idx_creators_user           on creators(user_id);
create index idx_creators_import_batch    on creators(import_batch_id);
create index idx_creators_status          on creators(status);
create index idx_creators_source          on creators(source);
create index idx_creators_last_active     on creators(last_active_at);
create index idx_campaigns_user           on campaigns(user_id);
create index idx_campaigns_type           on campaigns(campaign_type);
create index idx_campaigns_priority       on campaigns(priority);
create index idx_campaigns_owner          on campaigns(campaign_owner);
create index idx_import_batches_user      on import_batches(user_id);
create index idx_cc_user                  on campaign_creators(user_id);
create index idx_cc_campaign              on campaign_creators(campaign_id);
create index idx_cc_creator               on campaign_creators(creator_id);
create index idx_cc_import_batch          on campaign_creators(import_batch_id);
create index idx_cc_stage                 on campaign_creators(campaign_id, stage); -- Kanban board query
create index idx_cc_outreach_status       on campaign_creators(outreach_status);
create index idx_cc_payment_status        on campaign_creators(payment_status);
create index idx_cc_next_follow_up        on campaign_creators(next_follow_up_at);
create index idx_interactions_user        on interactions(user_id);
create index idx_interactions_creator     on interactions(creator_id);

-- =============================================================
-- updated_at auto-touch trigger
-- =============================================================
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

create trigger trg_users_updated       before update on users             for each row execute function set_updated_at();
create trigger trg_creators_updated    before update on creators          for each row execute function set_updated_at();
create trigger trg_campaigns_updated   before update on campaigns         for each row execute function set_updated_at();
create trigger trg_cc_updated          before update on campaign_creators for each row execute function set_updated_at();
