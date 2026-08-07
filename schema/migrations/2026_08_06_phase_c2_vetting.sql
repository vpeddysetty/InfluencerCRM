-- =============================================================
-- Phase C2: per-brand creator vetting
-- Date: 2026-08-06
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase C2
--
-- The decision this schema enforces (roadmap #5, decided 2026-08-02):
--   RULES MAY REJECT AND ADVANCE. THEY MAY NEVER APPROVE.
--
--   The asymmetry is deliberate. Rejection is reversible — a creator can be reinstated, and at
--   worst a brand missed one partnership. Approval grants access to briefs, assets and
--   eventually money, and the reasoning behind an approval is what a brand will be asked to
--   justify. Getting that wrong automatically is far more expensive than getting a rejection
--   wrong.
--
--   The roadmap is explicit that "auto-approval is not built, and the schema does not
--   anticipate it". So `action` below has no 'approve' value, and there is no `auto_approve`
--   flag for someone to find and flip. Adding auto-approval later means a migration and a
--   deliberate decision, which is the point.
--
-- Per brand, not per platform (roadmap C2): an agency running luxury beauty and one running
-- gaming have genuinely different thresholds, and hard-coding either is wrong for the other.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- C2.1: vetting status on the creator row.
-- -------------------------------------------------------------
-- Distinct from `status` (active/inactive), which predates this and means something else:
-- whether the brand is currently working with them, not whether they passed vetting.
alter table creator.creators
    add column if not exists vetting_status text not null default 'lead',
    add column if not exists vetting_decided_at timestamptz,
    add column if not exists vetting_decided_by_user_id uuid;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_creators_vetting_status') then
        alter table creator.creators add constraint ck_creators_vetting_status
            check (vetting_status in ('lead','pending','under_review','approved','rejected'));
    end if;
end $$;

comment on column creator.creators.vetting_status is
    'lead -> pending -> under_review -> approved | rejected. Only a human writes ''approved'' '
    '(roadmap #5); rules may write rejected/under_review and nothing else.';
comment on column creator.creators.vetting_decided_by_user_id is
    'Null when a rule decided. A non-null value is what distinguishes a human decision from an '
    'automated one without having to join vetting_events.';

create index if not exists idx_creators_vetting_status
    on creator.creators (brand_id, vetting_status);

-- -------------------------------------------------------------
-- C2.2: the rules themselves.
-- -------------------------------------------------------------
create table if not exists creator.vetting_rules (
    id             uuid primary key default gen_random_uuid(),

    brand_id       uuid not null,

    name           text not null,

    -- Ordered: the first matching rule wins, so a brand can put a specific exception above a
    -- general rule. Without ordering, overlapping rules would resolve arbitrarily.
    position       integer not null default 0,

    enabled        boolean not null default true,

    -- The condition, as { attribute, operator, value }. JSONB rather than columns because the
    -- attribute set is open (Group 1/3/4 of the catalogue) and each new attribute would
    -- otherwise be a migration.
    condition      jsonb not null,

    -- reject | review. NOT approve — see the header.
    action         text not null,

    -- Shown to the creator on a rejection and to the brand in the queue. A rejection a brand
    -- cannot explain is worse than no rule.
    reason         text,

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_vetting_rules_action') then
        -- The constraint IS the policy. Widening it to include 'approve' is a migration and a
        -- decision, not a config change.
        alter table creator.vetting_rules add constraint ck_vetting_rules_action
            check (action in ('reject','review'));
    end if;
end $$;

create index if not exists idx_vetting_rules_brand
    on creator.vetting_rules (brand_id, position);

comment on table creator.vetting_rules is
    'Per-brand vetting rules, evaluated in `position` order; first match wins. `action` is '
    'constrained to reject|review: rules may never approve (roadmap #5).';
comment on column creator.vetting_rules.condition is
    '{ "attribute": "follower_count", "operator": "lt", "value": 5000 }. Audience attributes '
    'only for demographics — never the creator''s own protected characteristics.';

-- -------------------------------------------------------------
-- C2.5: every decision, with the rule that caused it.
-- -------------------------------------------------------------
-- Append-only. This is what makes "why was I rejected?" answerable — to the creator, to the
-- brand, and to a regulator. Automated rejection without an audit trail is how a platform ends
-- up unable to explain itself.
create table if not exists creator.vetting_events (
    id             uuid primary key default gen_random_uuid(),

    brand_id       uuid not null,
    creator_id     uuid not null,

    from_status    text,
    to_status      text not null,

    -- Which rule fired, if any. Null for a human decision — and the pair (rule_id null,
    -- decided_by_user_id not null) is exactly how the two are told apart.
    rule_id        uuid,
    rule_name      text,

    decided_by_user_id uuid,

    -- Why. For a rule this is its `reason`; for a human, whatever they typed.
    reason         text,

    -- What the creator looked like when the decision was made. Without this a later metric
    -- refresh makes the decision look arbitrary in hindsight.
    snapshot       jsonb not null default '{}'::jsonb,

    occurred_at    timestamptz not null default now()
);

create index if not exists idx_vetting_events_creator
    on creator.vetting_events (creator_id, occurred_at desc);
create index if not exists idx_vetting_events_brand
    on creator.vetting_events (brand_id, occurred_at desc);

comment on table creator.vetting_events is
    'Append-only record of every vetting decision, automated or human, with the rule that '
    'caused it and a snapshot of the creator at the time. C2.5.';

-- -------------------------------------------------------------
-- C2.8: brand disputes a creator's audience quality.
-- -------------------------------------------------------------
-- Small and easy to skip, and should not be. It records what our own signal said AT THE TIME
-- of the complaint, which turns each dispute into a labelled example of the signal being
-- wrong. That is both the trigger for engaging a vendor and the only ground truth available
-- for tuning in-house thresholds (group2-build-vs-buy.md §5.1). Without it, "wait for
-- complaints" degrades into someone half-remembering that a few brands grumbled.
create table if not exists creator.creator_quality_reports (
    id             uuid primary key default gen_random_uuid(),

    brand_id       uuid not null,
    creator_id     uuid not null,
    reported_by_user_id uuid,

    -- fake_followers | low_engagement | bot_comments | other
    category       text not null,
    detail         text,

    -- What our signal said when the complaint was filed. Copied, not referenced: the creator
    -- row will move on, and the point is what we believed at the time.
    signal_snapshot jsonb not null default '{}'::jsonb,

    status         text not null default 'open',

    created_at     timestamptz not null default now(),
    resolved_at    timestamptz
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_quality_reports_status') then
        alter table creator.creator_quality_reports add constraint ck_quality_reports_status
            check (status in ('open','investigating','upheld','dismissed'));
    end if;
end $$;

create index if not exists idx_quality_reports_brand
    on creator.creator_quality_reports (brand_id, created_at desc);
create index if not exists idx_quality_reports_creator
    on creator.creator_quality_reports (creator_id);

comment on table creator.creator_quality_reports is
    'A brand disputing a creator''s audience quality (C2.8). signal_snapshot records what our '
    'own signal said at the time, making each dispute a labelled example for tuning — and the '
    'trigger for engaging a vendor (3 in a quarter, or 1 on a creator we rated clean).';

commit;

-- ---------------------------------------------------------------
-- Verification (expect 0, 0, 0 and the new columns):
--   select count(*) from creator.vetting_rules;
--   select count(*) from creator.vetting_events;
--   select count(*) from creator.creator_quality_reports;
--   select column_name from information_schema.columns
--    where table_schema='creator' and table_name='creators' and column_name like 'vetting%';
--
-- Rollback:
--   drop table if exists creator.vetting_events;
--   drop table if exists creator.vetting_rules;
--   drop table if exists creator.creator_quality_reports;
--   alter table creator.creators
--       drop column if exists vetting_status,
--       drop column if exists vetting_decided_at,
--       drop column if exists vetting_decided_by_user_id;
--   -- Additive; dropping returns creators to its Phase C shape. Note this DISCARDS the audit
--   -- trail, which is the one thing here that cannot be reconstructed.
-- ---------------------------------------------------------------
