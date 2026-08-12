-- =============================================================
-- Phase A: visual landing page builder (GrapesJS)
-- Date: 2026-08-05
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase A (A.1, A.4, A.5)
--
-- Purpose:
--   Three changes, each independently justified:
--
--   1. `document` — the GrapesJS document ({html, css}). Kept SEPARATE from the
--      existing `blocks` column rather than overwriting it. `blocks` still drives
--      the typed-block renderer that /s/{slug}/{creator} uses today; a page opened
--      in the new builder gains a `document` and is rendered from that instead.
--      Two columns means the cutover is per-page and reversible, and it is why no
--      existing page breaks the moment this ships.
--
--   2. `stage` — the PRD's eight-value lifecycle. Added here rather than in Phase D
--      because the builder needs somewhere to record "this page is a draft" beyond
--      the two-value `status`, and adding a column later to a populated table is
--      strictly more work than adding it to an empty one.
--
--   3. `landing_template_versions` — append-only history (A.5). Version history is
--      what makes co-editing (Phase G) safe without a CRDT: overwrites become
--      recoverable, which is the whole argument for deferring Yjs.
--
-- Deliberately NOT done here:
--   uq_landing_templates_campaign is LEFT IN PLACE. Allowing many pages per campaign
--   is a product decision (it changes what a "campaign landing page" means, and the
--   slug/coupon assignment logic assumes one), not a builder prerequisite. Phase A
--   ships against one-page-per-campaign; lifting it is a separate, deliberate change.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. The GrapesJS document.
-- -------------------------------------------------------------
-- Shape: { "html": "<section>…</section>", "css": ".sel{…}", "assets": [...] }
-- NULL means "this page has never been opened in the visual builder" — which is
-- exactly the signal the renderer uses to choose which path to take. A default of
-- '{}' would erase that distinction, so it is deliberately nullable with no default.
alter table content.landing_templates
    add column if not exists document jsonb;

comment on column content.landing_templates.document is
    'GrapesJS document { html, css }. NULL = never opened in the visual builder, '
    'in which case the legacy typed-block renderer reads `blocks` instead.';

-- -------------------------------------------------------------
-- 2. Lifecycle stage.
-- -------------------------------------------------------------
-- The existing `status` column stays (draft|published) because the public renderer
-- and the UI both read it. `stage` is the richer lifecycle the Kanban board syncs
-- against in Phase D. They are kept in step by the service, not by a trigger:
-- a trigger here would fire on the reconciliation job's writes too.
alter table content.landing_templates
    add column if not exists stage text not null default 'draft';

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_landing_templates_stage') then
        alter table content.landing_templates add constraint ck_landing_templates_stage
            check (stage in ('draft','review','approved','creator_assigned',
                             'content_needed','ready_to_publish','published','performance_tracking'));
    end if;
end $$;

comment on column content.landing_templates.stage is
    'Eight-value page lifecycle (PRD). Drives Kanban sync in Phase D. `status` remains '
    'the two-value draft|published flag the public renderer gates on.';

-- -------------------------------------------------------------
-- 3. Append-only version history (A.5).
-- -------------------------------------------------------------
-- Append-only: no update, no delete. A version is a fact about what the page looked
-- like at a moment, and rewriting history would defeat the point of keeping it.
create table if not exists content.landing_template_versions (
    id                  uuid primary key default gen_random_uuid(),

    landing_template_id uuid not null,
    brand_id            uuid not null,

    -- Monotonic per template, assigned by the service inside the save transaction.
    version_no          integer not null,

    -- The full snapshot, not a diff. Diffs are cheaper to store and far more
    -- expensive to restore correctly; at landing-page volume the storage does not
    -- matter and "restore" must be trivially correct.
    name                text,
    document            jsonb,
    blocks              jsonb,
    theme               jsonb,
    stage               text,

    created_by_user_id  uuid,
    created_at          timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_landing_versions_template_no') then
        alter table content.landing_template_versions
            add constraint uq_landing_versions_template_no unique (landing_template_id, version_no);
    end if;
end $$;

-- The common read is "history for this page, newest first".
create index if not exists idx_landing_versions_template
    on content.landing_template_versions (landing_template_id, version_no desc);

create index if not exists idx_landing_versions_brand
    on content.landing_template_versions (brand_id);

comment on table content.landing_template_versions is
    'Append-only snapshots of a landing page. Written on every save. Makes overwrites '
    'recoverable, which is what allows Phase G co-editing without a CRDT.';

-- No cross-context FK to identity.users / campaign.campaigns: Phase 5 severed those
-- deliberately (2026_08_02_phase5_sever_all_cross_context_fks.sql). brand_id is kept
-- denormalized on the version row so history is tenant-filterable without a join
-- back to a template that may since have been deleted.

commit;

-- ---------------------------------------------------------------
-- Verification (expect: document|jsonb, stage|text, then 0 versions):
--
--   select column_name, data_type from information_schema.columns
--    where table_schema='content' and table_name='landing_templates'
--      and column_name in ('document','stage');
--   select count(*) from content.landing_template_versions;
--
-- Rollback:
--   alter table content.landing_templates drop column if exists document;
--   alter table content.landing_templates drop column if exists stage;
--   drop table if exists content.landing_template_versions;
--   -- Safe: `document`/`stage` are additive and the legacy `blocks` renderer is
--   -- untouched, so dropping them returns the page to its pre-Phase-A behaviour.
-- ---------------------------------------------------------------
