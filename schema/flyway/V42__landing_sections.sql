-- =============================================================
-- PR-39 piece A: curated section editor — the `sections` column.
-- Date: 2026-08-24
-- Plan: docs/Curated-Section-Editor-Implementation-Plan.md §2A
-- Decision: docs/Landing-Editor-Framework-Evaluation.md
--
-- Purpose:
--   One additive column. `sections` is an ordered list of TYPED sections — the
--   vocabulary PageGenerationPort.Section already speaks — replacing the free-form
--   GrapesJS document as the way a page is authored.
--
--   Shape: [ { "type": "hero", "variant": "centred", "fields": { ... } }, ... ]
--
--   `fields` is an open object ON PURPOSE. Piece B designs the eight section types
--   and their variants, and pinning a per-type field schema in the database would
--   mean a migration every time a section gains a field. The renderer reads fields
--   by name and omits what is absent, so an unknown field is inert rather than
--   fatal — the same tolerance `blocks` has always had.
--
-- Why a THIRD column and not a rewrite of `document`:
--   Precedence at render time is `sections` -> `document` -> `blocks`. V24 already
--   proved this exact pattern when `document` was added beside `blocks`: two columns
--   mean the cutover is per-page and reversible, and no existing row is touched. The
--   one published page today keeps rendering from `document` until someone opens it
--   in the new editor. That is what makes this migration unable to break a live page.
--
--   It is also why there is deliberately NO backfill and no HTML-to-section parser.
--   Converting arbitrary builder markup back into typed sections is an unbounded
--   heuristic that would run against customer pages to save minutes of hand-rebuilding
--   one. Rejected in the plan (§2A) rather than deferred.
--
-- NULL vs '[]':
--   Nullable with no default, for the same reason `document` is. NULL means "this page
--   has never been authored in the section editor" and is what the renderer branches
--   on. A default of '[]' would erase that distinction and silently promote every
--   existing page onto the new render path with no content — i.e. blank pages.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. The section list on the page itself.
-- -------------------------------------------------------------
alter table content.landing_templates
    add column if not exists sections jsonb;

comment on column content.landing_templates.sections is
    'Ordered typed sections [{type,variant,fields}] authored in the curated editor (PR-39). '
    'NULL = never opened in the section editor, in which case the renderer falls back to '
    '`document` (GrapesJS) and then to `blocks` (legacy typed blocks).';

-- -------------------------------------------------------------
-- 2. The same column on version history.
-- -------------------------------------------------------------
-- Not optional. landing_template_versions snapshots `document`, `blocks` and `theme`
-- on every save, and restoreVersion writes a snapshot forward as a new save. A version
-- row that cannot carry `sections` would mean restoring any older version silently
-- BLANKS a section-authored page — history that destroys the thing it exists to
-- protect. The column has to land in both tables in the same migration.
alter table content.landing_template_versions
    add column if not exists sections jsonb;

comment on column content.landing_template_versions.sections is
    'Snapshot of landing_templates.sections at save time (PR-39). Restoring a version '
    'writes this forward, so it must be carried here or a restore would clear the page.';

commit;
