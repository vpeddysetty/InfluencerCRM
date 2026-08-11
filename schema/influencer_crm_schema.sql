-- =============================================================
-- Influencer CRM — Phase 1 schema (PostgreSQL)
-- Single-tenant-per-account model: every table hangs off `users`.
-- Target: Postgres 13+
-- =============================================================

-- ---- extensions --------------------------------------------
create extension if not exists "pgcrypto";   -- for gen_random_uuid()
create extension if not exists "citext";     -- for case-insensitive email
create extension if not exists vector;        -- for pgvector embeddings

-- EVERY OBJECT IN THIS FILE IS SCHEMA-QUALIFIED TO `public`, AND THAT IS NOT STYLE.
--
-- `if not exists` is idempotent PER SCHEMA, not per database. The application role runs with
--   search_path = identity, creator, campaign, workflow, attribution, finance, content, mapping, shared, public
-- so an UNQUALIFIED `create table if not exists users` checks only `identity` — the first entry — finds
-- nothing there, and creates a SECOND `users` in `identity` that SHADOWS the real one phase-5 placed in
-- its context schema.
--
-- That is not theoretical. Three re-runs against the deployed database created 21 shadow tables and 7
-- shadow enum types. `identity.creators` had 33 columns against the real `creator.creators`'s 45, so
-- every read failed with `column c1_0.classification_at does not exist` — while the migration itself
-- reported success and every container reported healthy.
--
-- Qualifying to `public` makes each statement mean what it says: create it in `public`, or find it
-- already there. Phase-5 then moves it into a context schema, and the next run's `if not exists` check
-- looks in `public`, does not find it, and... would recreate it. Which is why the guards below use
-- to_regtype()/pg_class lookups that are also `public`-qualified: the check and the create must agree
-- on WHERE, or they are not a guard at all.

-- ---- enums -------------------------------------------------
-- `create type` has no IF NOT EXISTS at all, so each is guarded explicitly. to_regtype('public.X')
-- rather than to_regtype('X'): the unqualified form resolves through search_path and would find a
-- relocated copy in `identity`, concluding the type exists when it does not exist HERE.
do $$ begin
    if to_regtype('public.user_role') is null then
        create type public.user_role as enum ('owner', 'marketer');
    end if;
end $$;
do $$ begin
    if to_regtype('public.platform_type') is null then
        create type public.platform_type as enum ('instagram', 'tiktok', 'youtube', 'other');
    end if;
end $$;
do $$ begin
    if to_regtype('public.campaign_status') is null then
        create type public.campaign_status as enum ('draft', 'active', 'completed', 'archived');
    end if;
end $$;
do $$ begin
    if to_regtype('public.interaction_type') is null then
        create type public.interaction_type as enum ('note', 'email', 'dm');
    end if;
end $$;
do $$ begin
    if to_regtype('public.content_review_status') is null then
        create type public.content_review_status as enum ('not_requested', 'requested', 'in_review', 'approved', 'rejected');
    end if;
end $$;
do $$ begin
    if to_regtype('public.attribution_platform') is null then
        create type public.attribution_platform as enum ('instagram', 'tiktok', 'youtube', 'shopify', 'amazon', 'woocommerce', 'direct', 'other');
    end if;
end $$;
do $$ begin
    if to_regtype('public.attribution_status') is null then
        create type public.attribution_status as enum ('pending', 'attributed', 'refunded', 'cancelled');
    end if;
end $$;

-- =============================================================
-- users  (the brand owner / solo marketer who signs up)
-- =============================================================
create table if not exists public.users (
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
create table if not exists public.creators (
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
create table if not exists public.campaigns (
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
create table if not exists public.import_batches (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references users(id) on delete cascade,
    source_filename text not null,
    source_file     bytea,
    hydration_status text not null default 'discovered',
    column_mapping  jsonb not null default '{}',       -- maps sheet columns -> fields
    row_count       integer not null default 0,
    created_at      timestamptz not null default now()
);

-- deferred fk: creators.import_batch_id -> import_batches.id
--
-- ADD CONSTRAINT has no IF NOT EXISTS, so a re-run would fail with "constraint already exists" and take
-- the whole migration - and therefore the whole platform - down. Guarded rather than dropped-and-added,
-- because dropping a foreign key on a populated table briefly removes the integrity check.
do $$ begin
    alter table public.creators
        add constraint creators_import_batch_fk
        foreign key (import_batch_id) references public.import_batches(id) on delete set null;
exception when duplicate_object then null;
end $$;

-- =============================================================
-- campaign_creators  (the creator-to-campaign relationship row)
-- One row = one creator's participation in one campaign.
-- =============================================================
create table if not exists public.campaign_creators (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_id         uuid not null references campaigns(id) on delete cascade,
    creator_id          uuid not null references creators(id)  on delete cascade,
    import_batch_id     uuid references public.import_batches(id) on delete set null,  -- null = added manually
    notes               text,
    tags                jsonb not null default '[]'::jsonb,
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

-- Seed recommendation for campaign types:
-- product seeding, sponsored content, gifting, affiliate campaigns, brand ambassador programs.

-- =============================================================
-- interactions  (relationship memory: notes, emails, dms)
-- =============================================================
create table if not exists public.interactions (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid not null references users(id) on delete cascade,
    creator_id uuid not null references creators(id) on delete cascade,
    type       interaction_type not null default 'note',
    body       text not null,
    created_at timestamptz not null default now()
);

-- =============================================================
-- mapping_examples  (import mapping memory / retrieval support)
-- =============================================================
create table if not exists public.mapping_examples (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid references users(id) on delete set null,
    template_name       text,
    source_signature    text not null,
    source_tab_names    text[] not null default '{}',
    source_columns      text[] not null default '{}',
    sample_values_json  jsonb not null default '{}'::jsonb,
    mappings_json       jsonb not null default '{}'::jsonb,
    quality_score       numeric(4,3) not null default 0.700,
    usage_count         integer not null default 0,
    is_active           boolean not null default true,
    signature_embedding vector(1536),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

-- =============================================================
-- workflow_boards  (kanban-like boards for brand-owner <-> creator relationship
-- management over a campaign lifecycle; NOT tied to any campaign). Up to 10 per
-- user, one active at a time (is_active = the radio selection). Enforced in DAO.
-- =============================================================
create table if not exists public.workflow_boards (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    name        text not null,
    start_date  date,
    end_date    date,
    is_active   boolean not null default false,
    position    integer not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists idx_workflow_boards_user
    on public.workflow_boards(user_id, position);

-- The ordered, customizable stages a board owns.
create table if not exists public.workflow_board_stages (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    board_id    uuid not null references workflow_boards(id) on delete cascade,
    stage_name  text not null,
    position    integer not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists idx_workflow_board_stages_board
    on public.workflow_board_stages(board_id, position);

-- A workflow card associates a campaign to a creator, carries its own name and
-- relationship attributes, and is the task placed on a board. Starts unassigned
-- (no board); dragging it onto a board/stage sets board_id + stage_id. Deleting
-- a board cascades its cards; deleting a stage nulls the card's stage_id.
create table if not exists public.workflow_cards (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    campaign_id   uuid not null references campaigns(id) on delete cascade,
    creator_id    uuid not null references creators(id) on delete cascade,
    board_id      uuid references workflow_boards(id) on delete cascade,
    stage_id      uuid references workflow_board_stages(id) on delete set null,
    name          text not null,
    status        text not null default 'todo',
    agreed_fee    numeric(12,2),
    fee_currency  text not null default 'USD',
    notes         text,
    tags          jsonb not null default '[]'::jsonb,
    position      integer not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index if not exists idx_workflow_cards_user      on public.workflow_cards(user_id);
create index if not exists idx_workflow_cards_board     on public.workflow_cards(board_id, stage_id, position);
create index if not exists idx_workflow_cards_campaign  on public.workflow_cards(campaign_id);
create index if not exists idx_workflow_cards_creator   on public.workflow_cards(creator_id);

-- =============================================================
-- influencer_campaign_codes  (creator campaign/referral/discount codes)
-- =============================================================
create table if not exists public.influencer_campaign_codes (
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
    updated_at          timestamptz not null default now(),
    unique (user_id, code)
);

-- =============================================================
-- influencer_sale_attributions  (attribution of brand sales to influencer code/campaign)
-- =============================================================
create table if not exists public.influencer_sale_attributions (
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

-- =============================================================
-- tenant indexes  (every list view filters by user_id)
-- =============================================================
create index if not exists idx_creators_user           on public.creators(user_id);
create index if not exists idx_creators_import_batch    on public.creators(import_batch_id);
create index if not exists idx_creators_status          on public.creators(status);
create index if not exists idx_creators_source          on public.creators(source);
create index if not exists idx_creators_last_active     on public.creators(last_active_at);
create index if not exists idx_campaigns_user           on public.campaigns(user_id);
create index if not exists idx_campaigns_type           on public.campaigns(campaign_type);
create index if not exists idx_campaigns_priority       on public.campaigns(priority);
create index if not exists idx_campaigns_owner          on public.campaigns(campaign_owner);
create index if not exists idx_import_batches_user      on public.import_batches(user_id);
create index if not exists idx_cc_user                  on public.campaign_creators(user_id);
create index if not exists idx_cc_campaign              on public.campaign_creators(campaign_id);
create index if not exists idx_cc_creator               on public.campaign_creators(creator_id);
create index if not exists idx_cc_import_batch          on public.campaign_creators(import_batch_id);
create index if not exists idx_cc_outreach_status       on public.campaign_creators(outreach_status);
create index if not exists idx_cc_payment_status        on public.campaign_creators(payment_status);
create index if not exists idx_cc_next_follow_up        on public.campaign_creators(next_follow_up_at);
create index if not exists idx_interactions_user        on public.interactions(user_id);
create index if not exists idx_interactions_creator     on public.interactions(creator_id);
create index if not exists idx_mapping_examples_active  on public.mapping_examples(is_active);
create index if not exists idx_mapping_examples_user    on public.mapping_examples(user_id);
create index if not exists idx_mapping_examples_quality on public.mapping_examples(quality_score desc);
create index if not exists idx_mapping_examples_embedding_cos
    on public.mapping_examples using ivfflat (signature_embedding vector_cosine_ops)
    with (lists = 100);
create index if not exists idx_icc_user                 on public.influencer_campaign_codes(user_id);
create index if not exists idx_icc_campaign_creator     on public.influencer_campaign_codes(campaign_id, creator_id);
create index if not exists idx_icc_code                 on public.influencer_campaign_codes(code);
create index if not exists idx_isa_user                 on public.influencer_sale_attributions(user_id);
create index if not exists idx_isa_code                 on public.influencer_sale_attributions(campaign_code_id);
create index if not exists idx_isa_campaign_creator     on public.influencer_sale_attributions(campaign_creator_id);
create index if not exists idx_isa_platform_status      on public.influencer_sale_attributions(platform, status);
create index if not exists idx_isa_occurred_at          on public.influencer_sale_attributions(occurred_at);

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

drop trigger if exists trg_users_updated on public.users;
create trigger trg_users_updated       before update on public.users             for each row execute function set_updated_at();
drop trigger if exists trg_creators_updated on public.creators;
create trigger trg_creators_updated    before update on public.creators          for each row execute function set_updated_at();
drop trigger if exists trg_campaigns_updated on public.campaigns;
create trigger trg_campaigns_updated   before update on public.campaigns         for each row execute function set_updated_at();
drop trigger if exists trg_cc_updated on public.campaign_creators;
create trigger trg_cc_updated          before update on public.campaign_creators for each row execute function set_updated_at();
drop trigger if exists trg_mapping_examples_updated on public.mapping_examples;
create trigger trg_mapping_examples_updated before update on public.mapping_examples for each row execute function set_updated_at();
drop trigger if exists trg_icc_updated on public.influencer_campaign_codes;
create trigger trg_icc_updated         before update on public.influencer_campaign_codes for each row execute function set_updated_at();
drop trigger if exists trg_isa_updated on public.influencer_sale_attributions;
create trigger trg_isa_updated         before update on public.influencer_sale_attributions for each row execute function set_updated_at();
