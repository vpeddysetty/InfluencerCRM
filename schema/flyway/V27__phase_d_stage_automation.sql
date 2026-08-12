-- =============================================================
-- Phase D: stage-driven automation and bidirectional Kanban sync
-- Date: 2026-08-05
-- Roadmap: docs/landing-page-builder-roadmap.md §4 and §5 Phase D
--
-- Context:
--   Decision #8 made the Kanban board WRITABLE: dragging a card changes the page stage, and
--   changing the page stage moves the card. Two writable state machines that must agree is
--   the shape that eventually produces a card in "Published" for a page still in draft.
--
--   §4 sets out four rules that keep them from diverging. Three of them need schema:
--
--   Rule 1 (content owns the transition) — no schema; it is the command endpoint.
--   Rule 2 (not every transition is legal) — the allowed-transition map, held in code so the
--          same rule applies to the board, the builder and the API.
--   Rule 4 (events carry a source, card writes are idempotent) — `stage_transitions` below.
--          The idempotency key is what turns a duplicated or retried event into a no-op
--          rather than a loop.
--   Reconciliation — `stage_transitions` is also the audit trail the nightly job compares
--          against, and the reason a divergence can be explained rather than just noticed.
--
-- D.6 stage mapping:
--   Per brand and per board, page stage -> board stage. Keyed on the STAGE ID, which only
--   became safe once 2026_08_05 fixed replace/ to preserve stage identity — before that a
--   rename minted new ids and every mapping would have dangled after the first stage edit.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. Page stage -> board stage mapping (D.6).
-- -------------------------------------------------------------
create table if not exists workflow.stage_mappings (
    id             uuid primary key default gen_random_uuid(),

    brand_id       uuid not null,
    board_id       uuid not null,

    -- One of the eight page stages. Text rather than an enum: the set is enforced by the
    -- content service, and an enum here would need a migration to add a stage while the
    -- check constraint on content.landing_templates already covers correctness.
    page_stage     text not null,

    -- The board stage a page in that stage should appear in. Nullable so a brand can say
    -- "this page stage has no place on the board" rather than being forced to invent one.
    stage_id       uuid,

    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_stage_mappings_board_page_stage') then
        alter table workflow.stage_mappings
            add constraint uq_stage_mappings_board_page_stage unique (board_id, page_stage);
    end if;
end $$;

create index if not exists idx_stage_mappings_brand on workflow.stage_mappings (brand_id);

comment on table workflow.stage_mappings is
    'Per-board mapping from a landing page stage to a board stage (roadmap D.6). '
    'Keyed on stage_id, which is only stable because replace/ preserves stage identity.';

-- -------------------------------------------------------------
-- 2. Transition log + idempotency (Rule 4, reconciliation).
-- -------------------------------------------------------------
create table if not exists workflow.stage_transitions (
    id                  uuid primary key default gen_random_uuid(),

    brand_id            uuid not null,
    landing_template_id uuid not null,

    from_stage          text,
    to_stage            text not null,

    -- Where the change came from: board | builder | api | reconciliation.
    -- Rule 4: a board-originated change must not echo back as a second move.
    source              text not null,

    -- Rule 4: one row per logical transition. A retried or duplicated event carries the same
    -- key and is absorbed here rather than moving a card twice.
    idempotency_key     text not null,

    card_id             uuid,
    applied             boolean not null default false,
    note                text,

    occurred_at         timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_stage_transitions_key') then
        alter table workflow.stage_transitions
            add constraint uq_stage_transitions_key unique (idempotency_key);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'ck_stage_transitions_source') then
        alter table workflow.stage_transitions add constraint ck_stage_transitions_source
            check (source in ('board','builder','api','reconciliation'));
    end if;
end $$;

create index if not exists idx_stage_transitions_template
    on workflow.stage_transitions (landing_template_id, occurred_at desc);

comment on table workflow.stage_transitions is
    'Every page-stage change, with its origin and an idempotency key. The key makes a '
    'duplicated or retried event a no-op instead of a second card move; the log is what the '
    'reconciliation job and any "why did this move?" question read.';

-- -------------------------------------------------------------
-- 3. Link a card to the page it tracks.
-- -------------------------------------------------------------
-- workflow_cards is keyed on (campaign, creator) and had no reference to a landing page, so
-- there was no way to find the card for a page. No FK: content is a separate context and
-- Phase 5 severed cross-context FKs deliberately.
alter table workflow.workflow_cards
    add column if not exists landing_template_id uuid;

create index if not exists idx_workflow_cards_landing_template
    on workflow.workflow_cards (landing_template_id)
    where landing_template_id is not null;

comment on column workflow.workflow_cards.landing_template_id is
    'The landing page this card tracks. Null for cards that are not page-driven — most cards '
    'predate Phase D and are campaign/creator tasks with no page.';

commit;

-- ---------------------------------------------------------------
-- Verification (expect 0, 0 and the new column):
--   select count(*) from workflow.stage_mappings;
--   select count(*) from workflow.stage_transitions;
--   select column_name from information_schema.columns
--    where table_schema='workflow' and table_name='workflow_cards'
--      and column_name='landing_template_id';
--
-- Rollback:
--   drop table if exists workflow.stage_mappings;
--   drop table if exists workflow.stage_transitions;
--   alter table workflow.workflow_cards drop column if exists landing_template_id;
--   -- Additive; dropping returns the board to its pre-Phase-D behaviour. Page stages on
--   -- content.landing_templates are untouched by this rollback.
-- ---------------------------------------------------------------
