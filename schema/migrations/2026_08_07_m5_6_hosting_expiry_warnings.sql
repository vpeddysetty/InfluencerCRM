-- =============================================================
-- M5.6: hosting expiry warnings
-- Date: 2026-08-07
-- Roadmap: PENDING-WORK-ROADMAP.md §"Recorded, not scheduled" — M5.6
--
-- The gap this closes: Phase E built the hosting window (decision #11 — two months free from
-- first publish) and the endpoints to extend it, but nothing ever told a brand the clock was
-- running out. A page simply stopped serving. The roadmap's own note on this item is that its
-- justification is "live customer harm the moment anyone publishes".
--
-- WHY A COLUMN AND NOT JUST A SCHEDULE. The scheduler runs daily, but a warning is owed once per
-- threshold. Without a record of what was sent, either:
--   - it re-sends every day inside the window (30 daily emails, then unsubscribes), or
--   - it fires only on an exact day-count match, and one missed run — a deploy, an outage, a
--     clock skew across a DST boundary — silently skips that warning forever.
-- Recording the last threshold sent makes the job idempotent AND recoverable: a run that was
-- missed still sends on the next run, because the question asked is "what is the smallest
-- threshold now passed that we have not sent yet", not "is today exactly day 7".
--
-- WHY ONE INTEGER RATHER THAN THREE TIMESTAMPS. The thresholds are strictly descending (30 → 7
-- → 1), so a single "smallest threshold already warned at" answers every question the scheduler
-- asks, and cannot represent the impossible state of having sent day-1 but not day-30.
--
-- NULL means no warning sent yet. Extending hosting resets it to NULL — a page whose window was
-- extended is owed a fresh set of warnings on the new deadline, and leaving 1 in place would
-- silence every warning for the rest of the page's life.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

alter table content.landing_templates
    add column if not exists hosting_warning_sent_at_days integer;

comment on column content.landing_templates.hosting_warning_sent_at_days is
    'Smallest expiry-warning threshold (in days remaining) already emailed for the CURRENT '
    'hosting window: 30, 7 or 1. NULL = none sent. Reset to NULL when hosting is extended, since '
    'a new deadline is owed a new set of warnings. One descending integer rather than three '
    'timestamps because the thresholds are ordered, so this cannot encode "warned at 1 but not 30".';

-- The scheduler sweeps pages whose window has started and is not yet fully warned. Partial, and
-- ordered to match that predicate: the vast majority of rows are unpublished (NULL expiry) or
-- already at threshold 1, and none of those need to be examined.
create index if not exists idx_landing_templates_expiry_warning
    on content.landing_templates (hosting_expires_at)
    where hosting_expires_at is not null
      and (hosting_warning_sent_at_days is null or hosting_warning_sent_at_days > 1);

commit;

-- -------------------------------------------------------------
-- Rollback
-- -------------------------------------------------------------
-- Additive and safe to drop: losing the column makes every page eligible for warnings again, so
-- the worst case is a duplicate warning, not a missed one.
--
--   begin;
--   drop index if exists content.idx_landing_templates_expiry_warning;
--   alter table content.landing_templates drop column if exists hosting_warning_sent_at_days;
--   commit;
