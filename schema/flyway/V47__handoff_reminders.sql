-- =============================================================
-- PR-44: remembering which handoff reminders have already been sent.
-- Date: 2026-08-27
-- Design: docs/Creator-Handoff-Design.md §3, lifecycle row 8
--
-- WHAT THIS IS FOR
--
--   Ghosting is the MODAL outcome in creator marketing, not an edge case. Every candidate design
--   for the handoff assumed forward motion -- brand hands off, creator edits, creator hands back --
--   and the common real ending is that nothing happens at all. Without a sweep the page sits with
--   turn = 'creator' indefinitely and the brand discovers it the week of the campaign.
--
--   V45 added turn_changed_at, which answers "how long has this been sitting with someone". This
--   adds the other half: "have we already said something about it?"
--
-- WHY A STAMP AND NOT A BOOLEAN
--
--   There are two nudges at different thresholds -- the creator at day three, the brand at day
--   seven -- so a flag would have to be two flags, and the pair would then have to be reset in step
--   whenever the turn moved. One nullable timestamp plus the existing turn_changed_at answers both
--   questions: a reminder counts only if it was sent AFTER the turn last moved, so handing the page
--   back and forth naturally re-arms the sweep with nothing to reset.
--
--   That is also what makes the sweep idempotent WITHIN an instance. Without it an hourly job would
--   see "three days elapsed" at hour 72, 73, 74 and email the creator every hour until they acted,
--   which is worse than no reminder -- it is how a sending domain gets marked as spam.
--
-- WHAT THIS DOES NOT FIX
--
--   OP-17: scheduling is plain Spring @Scheduled with no ShedLock, so a SECOND instance would still
--   send each nudge twice, both instances having read the row before either wrote the stamp. One
--   instance serves production today and the scheduled-publish sweep already lives with the same
--   constraint. Recorded here rather than left to be rediscovered.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

alter table content.landing_templates
    add column if not exists handoff_reminder_sent_at timestamptz;

comment on column content.landing_templates.handoff_reminder_sent_at is
    'When a handoff nudge was last sent for this page (PR-44). NULL means none since the turn last '
    'moved. Compared against turn_changed_at rather than cleared on handoff, so passing the page '
    'back and forth re-arms the sweep with nothing to reset. Without it an hourly sweep would '
    're-send every hour once a threshold passed.';

-- The sweep asks one question: which pages are waiting on someone, and for how long? The partial
-- index from V45 already covers (turn, turn_changed_at) and remains the right one -- this column is
-- read per candidate row, not filtered on, so it needs no index of its own. Adding one would cost
-- write throughput on every page save to speed up a query that touches a handful of rows an hour.

commit;

-- Rollback:
--   alter table content.landing_templates drop column if exists handoff_reminder_sent_at;
