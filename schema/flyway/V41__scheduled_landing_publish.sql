-- =============================================================
-- PR-35: scheduled landing page publish
-- Date: 2026-08-23
-- Roadmap: MASTER-ROADMAP.md §5 Stage 1 — PR-35 (AI campaign-page authoring, screen 6)
--
-- The gap this closes: the builder could publish now or not at all. A campaign that goes live at
-- 9am on a launch day meant somebody being awake at 9am to press a button, which is exactly the
-- kind of manual step a one-founder product cannot afford and a brand does not expect.
--
-- WHY A COLUMN AND NOT A JOBS TABLE. A separate scheduled_publishes table would be the general
-- answer, but a page has at most ONE pending publish time — scheduling a second replaces the
-- first, it does not queue behind it. A nullable column expresses that constraint in the schema
-- itself; a jobs table would need a partial unique index to say the same thing, plus a join on
-- every read, plus its own cleanup story for rows whose page was deleted.
--
-- WHY NOT REUSE `stage`. LandingStageMachine's eight values are an EDITORIAL workflow (draft →
-- review → approved → … → published). "Scheduled" is not a stage in that sense: a page awaiting
-- 9am is editorially `ready_to_publish` and stays there until the clock fires. Adding a ninth
-- value would make the board show a column that is really a timer, and would break the invariant
-- that a stage changes only when a human moves it.
--
-- WHY THE TIME IS UTC. Stored as timestamptz and compared against now(). The brand's local time
-- is a presentation concern — the UI collects a wall-clock time and converts. Storing local time
-- plus a zone name would mean the scheduler resolving zones on every sweep, and would make a page
-- scheduled across a DST boundary fire an hour early or late depending on when it was set.
--
-- NULL means not scheduled, which is the state of every existing row. The column is cleared when
-- the publish fires, so it always reads as "what is still owed", never as a history of what was
-- published — that history already exists in landing_page_transitions.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

alter table content.landing_templates
    add column if not exists scheduled_publish_at timestamptz;

comment on column content.landing_templates.scheduled_publish_at is
    'When this page should publish automatically, in UTC. NULL = not scheduled, which is also the '
    'state after a scheduled publish fires (the column records what is still owed, not history — '
    'landing_page_transitions holds the record of what actually happened). At most one pending '
    'time per page by construction: scheduling again overwrites rather than queuing.';

-- The scheduler asks one question every minute: which pages are due? Partial index so the vast
-- majority of rows — every page that is not scheduled — are never examined. Without the WHERE
-- clause this index would be almost entirely NULLs and the planner would ignore it.
create index if not exists idx_landing_templates_scheduled_publish
    on content.landing_templates (scheduled_publish_at)
    where scheduled_publish_at is not null;

commit;

-- -------------------------------------------------------------
-- Rollback
-- -------------------------------------------------------------
-- Additive. Dropping the column loses pending schedules, so any page awaiting a timed publish
-- would simply stay unpublished until someone publishes it by hand — it cannot cause a page to
-- publish that should not have, which is the direction that matters.
--
--   begin;
--   drop index if exists content.idx_landing_templates_scheduled_publish;
--   alter table content.landing_templates drop column if exists scheduled_publish_at;
--   commit;
