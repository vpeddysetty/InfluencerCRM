-- =============================================================
-- Phase C: creator onboarding — metric provenance and classification
-- Date: 2026-08-05
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase C (C.1-C.6)
--
-- The decision this schema enforces:
--   "Facts come from platform APIs, not from the model." Follower counts, engagement and
--   verification are READ from a platform. The LLM only classifies — niche, themes, risk
--   language — and never invents a metric. An LLM asked for a follower count will produce a
--   confident, plausible, wrong number, and a brand would spend money on it.
--
--   The schema is what makes that decision enforceable rather than a convention. A metric
--   and its provenance are written together, so "where did this number come from?" is always
--   answerable from the row itself.
--
-- Why provenance is a column and not a comment (roadmap §Phase C):
--   A brand looking at a 2%-engagement creator needs to know whether that is measured or
--   guessed, and a metric fetched four months ago is not a current fact. Both facts have to
--   survive in the data, not in the head of whoever wrote the importer.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. Provenance for the metric block.
-- -------------------------------------------------------------
-- Applies to the metric columns that already exist and are unused today:
-- follower_count, engagement_rate, average_views, last_active_at, audience_demographics.
--
-- One provenance record for the block rather than per column: they are fetched together in
-- a single call, so per-column stamps would be five copies of the same fact.
alter table creator.creators
    add column if not exists metrics_source text,
    add column if not exists metrics_fetched_at timestamptz,
    add column if not exists metrics_platform_verified boolean;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_creators_metrics_source') then
        alter table creator.creators add constraint ck_creators_metrics_source
            check (metrics_source is null or metrics_source in ('platform_api','mock','manual','import'));
    end if;
end $$;

comment on column creator.creators.metrics_source is
    'Where the metric block came from. platform_api = read from the platform. mock = a '
    'simulated adapter (development only; never to be shown to a brand as measured). '
    'manual = typed by a human. import = spreadsheet. NULL = no metrics captured.';
comment on column creator.creators.metrics_fetched_at is
    'When the metric block was read. A metric without a timestamp cannot be judged current.';
comment on column creator.creators.metrics_platform_verified is
    'Whether the platform reports the account as verified. Only meaningful with metrics_source '
    '= platform_api; NULL otherwise.';

-- -------------------------------------------------------------
-- 2. Classification, kept separate from metrics.
-- -------------------------------------------------------------
-- Deliberately distinct columns from the metric provenance above. Classification is the
-- model's output and metrics are the platform's; merging their provenance would lose exactly
-- the distinction this phase exists to preserve.
alter table creator.creators
    add column if not exists classification_source text,
    add column if not exists classification_at timestamptz,
    add column if not exists content_themes text[] not null default '{}',
    add column if not exists risk_flags text[] not null default '{}';

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_creators_classification_source') then
        alter table creator.creators add constraint ck_creators_classification_source
            check (classification_source is null or classification_source in ('llm','heuristic','manual'));
    end if;
end $$;

comment on column creator.creators.classification_source is
    'llm = a model classified this. heuristic = the deterministic fallback when no model was '
    'available. manual = a human set it. Never platform_api — a platform does not classify.';
comment on column creator.creators.content_themes is
    'What the creator posts about, as summarised by the classifier. Distinct from niche, '
    'which is a single bucket.';
comment on column creator.creators.risk_flags is
    'Brand-safety flags raised by the classifier (adult, alcohol, gambling, politics, '
    'controversy). Advisory input to a human decision, never an automatic rejection.';

-- -------------------------------------------------------------
-- 3. Lead capture.
-- -------------------------------------------------------------
-- A creator signing up through a landing page is a lead against the page's brand. The
-- existing tenancy rule holds unchanged: one creator.creators row per (creator, brand), so a
-- creator signing up to five brands has five rows and no brand sees another's.
alter table creator.creators
    add column if not exists lead_source text,
    add column if not exists lead_landing_template_id uuid;

comment on column creator.creators.lead_source is
    'How this creator entered: landing_page, manual, import. Distinct from `source`, which '
    'predates this and is free text.';
comment on column creator.creators.lead_landing_template_id is
    'The landing page whose signup block created this lead. No FK — content is a separate '
    'context (Phase 5 severed cross-context FKs) and the lead must outlive the page.';

create index if not exists idx_creators_lead_template
    on creator.creators (lead_landing_template_id)
    where lead_landing_template_id is not null;

commit;

-- ---------------------------------------------------------------
-- Verification:
--   select column_name from information_schema.columns
--    where table_schema='creator' and table_name='creators'
--      and column_name like 'metrics_%' or column_name like 'classification_%';
--
-- Rollback:
--   alter table creator.creators
--       drop column if exists metrics_source, drop column if exists metrics_fetched_at,
--       drop column if exists metrics_platform_verified,
--       drop column if exists classification_source, drop column if exists classification_at,
--       drop column if exists content_themes, drop column if exists risk_flags,
--       drop column if exists lead_source, drop column if exists lead_landing_template_id;
--   -- Purely additive; dropping returns creators to its pre-Phase-C shape. The metric
--   -- columns themselves (follower_count etc.) predate this migration and are untouched.
-- ---------------------------------------------------------------
