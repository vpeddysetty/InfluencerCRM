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
create type workflow_actor as enum ('brand_owner', 'creator', 'system');
create type workflow_task_status as enum ('todo', 'in_progress', 'blocked', 'submitted', 'approved', 'rejected', 'done');
create type approval_decision as enum ('approved', 'changes_requested', 'rejected');
create type payout_status as enum ('draft', 'pending', 'scheduled', 'paid', 'failed', 'cancelled');
create type attribution_platform as enum ('instagram', 'tiktok', 'youtube', 'shopify', 'amazon', 'woocommerce', 'direct', 'other');
create type attribution_status as enum ('pending', 'attributed', 'refunded', 'cancelled');

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
    source_file     bytea,
    hydration_status text not null default 'discovered',
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

-- =============================================================
-- campaign_type_workflow_stages  (workflow definition by campaign type)
-- =============================================================
create table campaign_type_workflow_stages (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    campaign_type text not null,
    stage_key     pipeline_stage not null,
    stage_label   text not null,
    position      integer not null default 0,
    is_active     boolean not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    unique (user_id, campaign_type, stage_key)
);

-- Seed recommendation for campaign types:
-- product seeding, sponsored content, gifting, affiliate campaigns, brand ambassador programs.

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
-- mapping_examples  (import mapping memory / retrieval support)
-- =============================================================
create table mapping_examples (
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
-- creator_workflow_tasks  (operational work items between brand-owner and creator)
-- =============================================================
create table creator_workflow_tasks (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid not null references users(id) on delete cascade,
    campaign_creator_id uuid not null references campaign_creators(id) on delete cascade,
    title               text not null,
    description         text,
    assignee_actor      workflow_actor not null default 'brand_owner',
    assignee_creator_id uuid references creators(id) on delete set null,
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

-- =============================================================
-- creator_workflow_approvals  (submission / review rounds for creator deliverables)
-- =============================================================
create table creator_workflow_approvals (
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

-- =============================================================
-- creator_workflow_payments  (creator payout operations)
-- =============================================================
create table creator_workflow_payments (
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

-- =============================================================
-- creator_workflow_events  (immutable timeline for auditing handoffs)
-- =============================================================
create table creator_workflow_events (
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

-- =============================================================
-- influencer_campaign_codes  (creator campaign/referral/discount codes)
-- =============================================================
create table influencer_campaign_codes (
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
create table influencer_sale_attributions (
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
create index idx_mapping_examples_active  on mapping_examples(is_active);
create index idx_mapping_examples_user    on mapping_examples(user_id);
create index idx_mapping_examples_quality on mapping_examples(quality_score desc);
create index idx_mapping_examples_embedding_cos
    on mapping_examples using ivfflat (signature_embedding vector_cosine_ops)
    with (lists = 100);
create index idx_cwt_user                 on creator_workflow_tasks(user_id);
create index idx_cwt_campaign_creator     on creator_workflow_tasks(campaign_creator_id);
create index idx_cwt_status_due           on creator_workflow_tasks(status, due_at);
create index idx_cwa_user                 on creator_workflow_approvals(user_id);
create index idx_cwa_campaign_creator     on creator_workflow_approvals(campaign_creator_id);
create index idx_cwa_submitted_at         on creator_workflow_approvals(submitted_at);
create index idx_cwp_user                 on creator_workflow_payments(user_id);
create index idx_cwp_campaign_creator     on creator_workflow_payments(campaign_creator_id);
create index idx_cwp_status_scheduled     on creator_workflow_payments(status, scheduled_at);
create index idx_cwe_user                 on creator_workflow_events(user_id);
create index idx_cwe_campaign_creator     on creator_workflow_events(campaign_creator_id);
create index idx_cwe_created_at           on creator_workflow_events(created_at);
create index idx_icc_user                 on influencer_campaign_codes(user_id);
create index idx_icc_campaign_creator     on influencer_campaign_codes(campaign_id, creator_id);
create index idx_icc_code                 on influencer_campaign_codes(code);
create index idx_isa_user                 on influencer_sale_attributions(user_id);
create index idx_isa_code                 on influencer_sale_attributions(campaign_code_id);
create index idx_isa_campaign_creator     on influencer_sale_attributions(campaign_creator_id);
create index idx_isa_platform_status      on influencer_sale_attributions(platform, status);
create index idx_isa_occurred_at          on influencer_sale_attributions(occurred_at);

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
create trigger trg_mapping_examples_updated before update on mapping_examples for each row execute function set_updated_at();
create trigger trg_cwt_updated         before update on creator_workflow_tasks    for each row execute function set_updated_at();
create trigger trg_cwp_updated         before update on creator_workflow_payments for each row execute function set_updated_at();
create trigger trg_icc_updated         before update on influencer_campaign_codes for each row execute function set_updated_at();
create trigger trg_isa_updated         before update on influencer_sale_attributions for each row execute function set_updated_at();
