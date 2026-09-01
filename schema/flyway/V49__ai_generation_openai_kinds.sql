-- Meter the OpenAI path too (roadmap PR-62).
--
-- WHY THIS IS NEEDED NOW AND WAS NOT BEFORE. V48 metered the Anthropic page generator and left the
-- Python agent_service alone, on the reasoning that it was the same user action on a different
-- vendor and could follow. That was survivable while the agent had no key: OP-27 found
-- `influencrm-prod/openai-api-key` had never held a value, so every call there fell back to a
-- keyword matcher and cost nothing. The key was set on 2026-09-01 and the model is live, so that
-- path now spends real money on every creator lookup with nothing counting it.
--
-- WHAT IS AND IS NOT COUNTED, and the distinction is the whole point:
--
--   classify        COUNTED. Runs once per CREATOR, on preview and on save, with no caching --
--                   previewing a roster of fifty while deciding bills over a hundred times. This is
--                   the per-row multiplier V48 exists to stop, on the vendor V48 did not cover.
--
--   brief_draft     COUNTED. The "Draft with AI" button. Same user action as an Anthropic draft,
--                   different vendor; charging for one and not the other would be arbitrary.
--
--   column_mapping  RECORDED, NEVER COUNTED. The spreadsheet import sends only the column HEADERS,
--                   so a 10,000-row roster and a 10-row one cost exactly one call each. It is
--                   bounded by the number of imports, never by their size, and metering it would
--                   tax the activation moment for a fraction of a cent. Recorded anyway, because
--                   "which kind of call ran up the bill" is the question this table exists to
--                   answer and a gap in it is worse than a row nobody counts.
--
-- The free tier is 20/month and V48 chose that number as "far more than authoring a campaign in
-- good faith takes" -- measured against page drafts. A budget silently shared with imports would
-- make 20 stop meaning 20 drafts and bite during the activation the feature exists to produce,
-- which is why column_mapping is excluded rather than simply left uncounted by accident.

begin;

-- The CHECK is replaced rather than dropped: an unrecognised kind counted against someone's
-- allowance with no way to say what it was is exactly what the original constraint prevented, and
-- that reasoning does not weaken by adding vendors.
alter table shared.ai_generation_events
    drop constraint if exists ai_generation_events_kind_check;

alter table shared.ai_generation_events
    add constraint ai_generation_events_kind_check
    check (kind in ('generate', 'regenerate', 'rewrite', 'classify', 'brief_draft', 'column_mapping'));

comment on column shared.ai_generation_events.kind is
    'Which call was made. generate/regenerate/rewrite are the Anthropic page generator; classify, '
    'brief_draft and column_mapping are the OpenAI agent. column_mapping is recorded but never '
    'counted -- it is one bounded call per import regardless of file size.';

-- `generator` gains no constraint, deliberately. It has none today and adding one here would fail
-- any row written by a vendor added later -- during a deploy, on a table whose whole job is to keep
-- recording. The excluded-from-counting set is enforced in the query, not the schema.
comment on column shared.ai_generation_events.generator is
    'Which implementation served it: anthropic, openai, or template. Rows with generator = '
    'template are recorded and never counted -- see the partial index.';

-- The partial index excludes template rows from the count. column_mapping is excluded in the
-- query rather than here: it is a BILLED call and belongs in the index for the "what did this
-- account actually spend" question, even though it is not charged against the ceiling.
commit;
