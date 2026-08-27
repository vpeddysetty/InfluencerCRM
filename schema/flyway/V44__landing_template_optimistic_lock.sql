-- =============================================================
-- OP-18: optimistic locking on content.landing_templates.
-- Date: 2026-08-27
-- Design: docs/Creator-Handoff-Design.md §1
--
-- Purpose:
--   Stop a brand and a creator editing the same page from silently overwriting each other.
--
-- The failure this closes:
--   Both sides load the page, both edit, both save. The second write wins completely and the
--   first person's work is gone with no error, no conflict, and nothing on screen to notice.
--   Landing pages are the one row in this system with TWO editors by design — that is the whole
--   point of the collaboration feature — so this is not the theoretical race it would be on a
--   brand-only table. The version snapshot in landing_template_versions makes the loss
--   RECOVERABLE, but only if someone realises it happened; this makes it not happen.
--
-- Why a column rather than SELECT ... FOR UPDATE:
--   The edit window is a human one, minutes long, spanning a page load and a save from a browser.
--   A database lock held across that is a lock held across user think-time, which is how a
--   deadlock or an exhausted pool starts. Optimistic locking assumes the collision is rare (it
--   is), detects it at write time, and costs nothing in the common case.
--
-- Why NOT NULL DEFAULT 0 and not nullable:
--   Hibernate treats a null @Version as "this entity is new" and will attempt an INSERT. Every
--   existing row therefore needs a real number before the annotation goes live, which the DEFAULT
--   supplies for the backfill and for any writer that has not been updated yet.
--
-- Ordering note:
--   V44 was reserved in MASTER-ROADMAP.md §10 for PR-40's `turn` axis. OP-18 ships first, so it
--   takes this number and PR-40 moves to the next free one. The version is a position in the
--   sequence, not an identifier of a plan — see the header in V36.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

alter table content.landing_templates
    add column if not exists version bigint not null default 0;

comment on column content.landing_templates.version is
    'Optimistic-lock counter, incremented by Hibernate on every update (OP-18). A write carrying '
    'a stale value is rejected so a brand and a creator editing concurrently get a conflict '
    'rather than a silent overwrite. NOT NULL because Hibernate reads a null @Version as a new '
    'entity and would INSERT instead of UPDATE.';

commit;

-- Rollback, if this ever has to come out. Drop the @Version annotation FIRST and deploy that,
-- then drop the column — in the other order every write fails between the two steps:
--   alter table content.landing_templates drop column if exists version;
