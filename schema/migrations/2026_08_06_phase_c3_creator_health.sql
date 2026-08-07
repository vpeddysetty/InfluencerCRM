-- =============================================================
-- Phase C3: creator health monitoring
-- Date: 2026-08-06
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase C3
--
-- Vetting is a gate; this is what happens after someone is through it. A creator approved at
-- 50k followers who quietly declines to 5k is a live problem, and today nothing would notice.
--
-- Two decisions this schema encodes:
--
-- 1. ALERTS INFORM A DECISION; THEY NEVER TAKE ONE. (Roadmap #13, decided 2026-08-02.)
--    A decline raises a flag for the brand and a human decides whether to keep, pause or end
--    the relationship. Nothing auto-revokes. The reasoning is the same asymmetry as
--    auto-approval and stronger here: a creator mid-campaign has delivered work, may be owed
--    money, and may have declined other offers to take this one. Metrics also dip for
--    legitimate reasons — an algorithm change, a break, a seasonal niche, or one viral post
--    inflating the previous baseline.
--
--    So `creator_health_alerts` has acknowledge/snooze/act, and NO column that revokes
--    anything. Vetting status is untouched by this phase.
--
-- 2. SNAPSHOTS, NOT OVERWRITES.
--    `creator_metric_snapshots` is append-only, one row per fetch. Without history there is no
--    trend, no way to distinguish a slide from a correction, and no evidence when a brand asks
--    why an alert fired. The current value stays on `creators` for fast reads; the series
--    lives alongside it.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- C3.2: append-only metric history.
-- -------------------------------------------------------------
create table if not exists creator.creator_metric_snapshots (
    id                 uuid primary key default gen_random_uuid(),

    brand_id           uuid not null,
    creator_id         uuid not null,

    follower_count     bigint,
    engagement_rate    numeric(6,2),
    average_views      bigint,
    last_active_at     timestamptz,

    -- Provenance travels with the numbers here too (Phase C). A trend built from a mix of
    -- measured and simulated points is not a trend, and the only way to tell is to record it.
    metrics_source     text,

    captured_at        timestamptz not null default now()
);

-- The read is always "this creator's series, newest first".
create index if not exists idx_metric_snapshots_creator
    on creator.creator_metric_snapshots (creator_id, captured_at desc);
create index if not exists idx_metric_snapshots_brand
    on creator.creator_metric_snapshots (brand_id, captured_at desc);

comment on table creator.creator_metric_snapshots is
    'Append-only metric history, one row per fetch (C3.2). Never updated: a snapshot records '
    'what was true at a moment, and rewriting it destroys the trend it exists to support.';

-- -------------------------------------------------------------
-- C3.3: per-brand alert thresholds.
-- -------------------------------------------------------------
-- Per brand because a 20% drop means very different things at 5k and 5M followers, and because
-- platform defaults are how alert fatigue starts. An alert nobody reads is worse than no
-- alert, since it looks like coverage.
create table if not exists creator.health_thresholds (
    id                        uuid primary key default gen_random_uuid(),

    brand_id                  uuid not null,

    follower_drop_pct         numeric(5,2) not null default 20.00,
    engagement_drop_pct       numeric(5,2) not null default 30.00,
    inactive_days             integer      not null default 45,

    -- Window over which a drop is measured. 30 days by default, per the roadmap.
    window_days               integer      not null default 30,

    -- A new risk flag always alerts regardless of the numeric thresholds: brand safety is not
    -- a matter of degree.
    alert_on_new_risk_flag    boolean      not null default true,

    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_health_thresholds_brand') then
        alter table creator.health_thresholds add constraint uq_health_thresholds_brand unique (brand_id);
    end if;
end $$;

comment on table creator.health_thresholds is
    'Per-brand alert thresholds (C3.3). Per brand, not platform-wide: a 20% drop means very '
    'different things at 5k and 5M, and platform defaults are how alert fatigue starts.';

-- -------------------------------------------------------------
-- C3.4/C3.5: the alerts themselves.
-- -------------------------------------------------------------
create table if not exists creator.creator_health_alerts (
    id                 uuid primary key default gen_random_uuid(),

    brand_id           uuid not null,
    creator_id         uuid not null,

    -- follower_drop | engagement_drop | inactive | new_risk_flag
    alert_type         text not null,

    -- Human-readable, because this is what someone reads in a digest.
    summary            text not null,

    -- The numbers behind it. An alert a brand cannot check is an alert they learn to ignore.
    previous_value     numeric(14,2),
    current_value      numeric(14,2),
    change_pct         numeric(7,2),

    -- open -> acknowledged | snoozed | acted. There is deliberately NO status that revokes
    -- anything: the alert informs, a human decides (roadmap #13).
    status             text not null default 'open',
    snoozed_until      timestamptz,

    -- Recorded when someone acts, so "we saw it and decided to keep them" is on the record
    -- just as much as "we ended it".
    resolution_note    text,
    resolved_by_user_id uuid,
    resolved_at        timestamptz,

    created_at         timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_health_alerts_status') then
        alter table creator.creator_health_alerts add constraint ck_health_alerts_status
            check (status in ('open','acknowledged','snoozed','acted'));
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_health_alerts_type') then
        alter table creator.creator_health_alerts add constraint ck_health_alerts_type
            check (alert_type in ('follower_drop','engagement_drop','inactive','new_risk_flag'));
    end if;
    -- One OPEN alert of a given type per creator. Without this a weekly refresh would raise the
    -- same "followers down 22%" alert every week until someone acted, which is precisely the
    -- alert fatigue the roadmap warns against.
    if not exists (select 1 from pg_indexes where indexname = 'uq_health_alerts_open') then
        create unique index uq_health_alerts_open
            on creator.creator_health_alerts (creator_id, alert_type)
            where status = 'open';
    end if;
end $$;

create index if not exists idx_health_alerts_brand
    on creator.creator_health_alerts (brand_id, status, created_at desc);

comment on table creator.creator_health_alerts is
    'Health alerts (C3.4). Statuses are acknowledge/snooze/act only — nothing here revokes a '
    'creator. A creator mid-campaign has delivered work and may be owed money; the alert '
    'informs a human decision (roadmap #13).';

commit;

-- ---------------------------------------------------------------
-- Verification (expect 0, 0, 0):
--   select count(*) from creator.creator_metric_snapshots;
--   select count(*) from creator.health_thresholds;
--   select count(*) from creator.creator_health_alerts;
--
-- Rollback:
--   drop table if exists creator.creator_health_alerts;
--   drop table if exists creator.health_thresholds;
--   drop table if exists creator.creator_metric_snapshots;
--   -- Additive; nothing in Phase C or C2 depends on these. Dropping loses the metric history,
--   -- which cannot be reconstructed — the platform APIs return current values, not past ones.
-- ---------------------------------------------------------------
