-- =============================================================
-- AI generation metering.
-- Date: 2026-08-31
--
-- WHY THIS EXISTS, AND WHAT IT IS NOT
--
--   Every landing-page generation and section rewrite is a billed Anthropic call
--   (WEBE_PAGE_GENERATION_PROVIDER=anthropic, switched on 2026-08-24). Nothing counted them, so a
--   single account in a retry loop could run up spend with no ceiling and no way to see it after
--   the fact.
--
--   This is a COST CEILING, not a paywall. The free allowance is deliberately generous — far more
--   than anyone authoring a campaign in good faith will use — because the blank canvas is the
--   problem this feature exists to remove, and metering it into uselessness would trade the
--   activation the product needs for spend it does not currently have.
--
-- WHY A ROW PER EVENT RATHER THAN A COUNTER
--
--   A counter column would be one UPDATE and would answer "how many this month" just as well. It
--   answers nothing else: which account, which kind of call, whether one campaign accounted for the
--   whole month, whether a spike was one user or fifty. At this volume the row count is trivial and
--   the questions are not hypothetical — the first time spend looks wrong, the answer has to come
--   from data rather than from a guess.
--
--   It also makes the period a query rather than a schema decision. A monthly reset stored as a
--   counter needs a job to zero it, and that job is a thing that can fail silently; counting rows
--   since the start of the month needs nothing to run at all.
--
-- WHY account_id AND NOT brand_id
--
--   Plans are billed per ACCOUNT — PlanPolicy resolves limits by account, and an agency on one plan
--   holds several brands. Metering per brand would multiply the allowance by however many brands an
--   account happened to create, which is the opposite of a ceiling.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

create table if not exists shared.ai_generation_events (
    id           uuid primary key default gen_random_uuid(),
    account_id   uuid        not null,
    brand_id     uuid,
    -- 'generate' (a full page, several variants), 'regenerate' (one variant), 'rewrite' (one
    -- section). Checked rather than free text: an unrecognised kind would be counted against the
    -- allowance without anyone being able to say what it was.
    kind         text        not null
                 check (kind in ('generate', 'regenerate', 'rewrite')),
    -- Which implementation served it. A `template` generation costs nothing and must not consume
    -- the allowance, but it is still worth recording: the difference between "the model was used"
    -- and "the fallback ran" is the first thing to ask when output quality is questioned.
    generator    text        not null default 'anthropic',
    created_at   timestamptz not null default now()
);

comment on table shared.ai_generation_events is
    'One row per billed AI call. Counted per account per calendar month against PlanPolicy''s '
    'allowance — a cost ceiling, not a paywall.';

-- The only query this table serves: how many billed calls has this account made since a given
-- instant. Partial on the billed generators, because template rows are recorded but never counted
-- and there is no reason to carry them in the index.
create index if not exists idx_ai_generation_events_account_month
    on shared.ai_generation_events (account_id, created_at desc)
    where generator <> 'template';

-- Read by the BFF through the DAO's role, like every other shared table.
grant select, insert on shared.ai_generation_events to influencercrm_user;

commit;
