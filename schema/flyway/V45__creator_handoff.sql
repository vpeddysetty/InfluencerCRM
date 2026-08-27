-- =============================================================
-- PR-40: the creator handoff spine — whose turn it is, and where creator sessions live.
-- Date: 2026-08-27
-- Design: docs/Creator-Handoff-Design.md §3
--
-- Three things, all additive:
--   1. content.landing_templates.turn        — whose move is it?
--   2. content.page_handoffs                 — the audit trail of every pass
--   3. identity.creator_portal_sessions      — sessions that survive a deploy
--
-- Note on the number: §10 of MASTER-ROADMAP.md reserved V44 for this, but OP-18 shipped first and
-- took it. The version is a position in the sequence, not an identifier of a plan.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------------------
-- 1. turn — orthogonal to stage, and that is the whole design
-- -------------------------------------------------------------------------
-- `stage` answers "how far along is this page?" and `turn` answers "whose move is it?". They are
-- genuinely independent: a page sits at content_needed while the turn bounces brand -> creator ->
-- brand three times over. Collapsing them into one column is the obvious-looking simplification,
-- and it breaks the first time a creator hands work back that the brand then hands forward again.
--
-- NULL is a real, common state and not an unknown: it means nobody owes anything — a solo draft
-- nobody has been invited to, or a published page where the work is finished. That is why this is
-- nullable rather than defaulted to 'brand'.
alter table content.landing_templates
    add column if not exists turn text;

alter table content.landing_templates
    add column if not exists turn_changed_at timestamptz;

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'ck_landing_templates_turn'
    ) then
        alter table content.landing_templates add constraint ck_landing_templates_turn
            check (turn is null or turn in ('brand', 'creator'));
    end if;
end $$;

comment on column content.landing_templates.turn is
    'Whose move it is: brand, creator, or NULL for nobody. Deliberately separate from `stage`, '
    'which records how far along the page is — the two change for different reasons and a page '
    'can bounce turn several times without its stage moving at all (PR-40).';

comment on column content.landing_templates.turn_changed_at is
    'When the turn last moved. Drives the abandonment sweep in PR-44 — ghosting is the modal '
    'outcome in creator marketing, so "nothing has happened for N days" has to be answerable. '
    'Distinct from updated_at, which any edit moves.';

-- The abandonment sweep asks one question: which pages have been waiting on someone too long?
-- Partial index, so the pages nobody owes anything on are never examined — same reasoning as the
-- scheduled-publish index in V41.
create index if not exists idx_landing_templates_turn
    on content.landing_templates (turn, turn_changed_at)
    where turn is not null;

-- -------------------------------------------------------------------------
-- 2. page_handoffs — the audit trail
-- -------------------------------------------------------------------------
-- Separate from landing_page_transitions, which records STAGE changes. A handoff is not a stage
-- change: the commonest one (a creator sending work back) moves the turn and leaves the stage
-- exactly where it was. Recording them in the transitions table would mean rows whose from_stage
-- and to_stage are equal, which every existing reader of that table would have to learn to ignore.
create table if not exists content.page_handoffs (
    id                    uuid primary key,
    landing_template_id   uuid not null,
    brand_id              uuid not null,
    -- Who it moved to, and who moved it. actor_user_id is null when the creator acted (they have
    -- no user row); actor_creator_identity_id is null when the brand did. Exactly one is set, and
    -- the check below enforces that rather than trusting the writer.
    to_turn               text not null,
    actor_user_id         uuid,
    actor_creator_identity_id uuid,
    -- The note the brand writes when handing off ("here's what I'd like you to bring") or the
    -- creator writes when handing back. Optional, and never AI-sent without review — see §5.
    note                  text,
    -- Idempotency key, PER OCCURRENCE. Not templateId:from->to: work legitimately goes round the
    -- loop more than once, and V24's transition log learned this the expensive way — the second
    -- pass collided with the first and vanished from the audit trail while the stage still moved.
    idempotency_key       text not null,
    created_at            timestamptz not null default now(),
    constraint ck_page_handoffs_to_turn check (to_turn in ('brand', 'creator')),
    constraint ck_page_handoffs_actor check (
        (actor_user_id is not null and actor_creator_identity_id is null)
        or (actor_user_id is null and actor_creator_identity_id is not null)
    )
);

create unique index if not exists uq_page_handoffs_idempotency
    on content.page_handoffs (idempotency_key);

create index if not exists idx_page_handoffs_template
    on content.page_handoffs (landing_template_id, created_at desc);

comment on table content.page_handoffs is
    'Every pass of a landing page between a brand and a creator (PR-40). Distinct from '
    'landing_page_transitions: a handoff moves the TURN, and the commonest one leaves the stage '
    'untouched.';

-- -------------------------------------------------------------------------
-- 3. creator_portal_sessions — sessions that survive a deploy
-- -------------------------------------------------------------------------
-- These lived in a ConcurrentHashMap, which the code called out honestly as infrastructure-ahead-
-- of-need while the portal had no real users. It stops being acceptable here, and not because of
-- multi-instance: an ASG instance refresh is the LIVE step of every deploy in this project, so an
-- in-memory store signs out every creator on every release. A creator halfway through editing a
-- page would lose their session and their draft to a deploy they cannot see coming.
--
-- The token is stored HASHED. It is a bearer credential, so a database read — a backup, a log, an
-- errant query — would otherwise hand over live sessions. sha256 and not bcrypt deliberately: this
-- is a 256-bit random value, not a password, so there is nothing to brute-force and the per-request
-- cost of a slow hash would be paid on every single call.
create table if not exists identity.creator_portal_sessions (
    token_hash            text primary key,
    creator_identity_id   uuid not null,
    created_at            timestamptz not null default now(),
    expires_at            timestamptz not null,
    -- Set when the session is explicitly ended. The row is kept rather than deleted so that "was
    -- this session revoked or did it simply expire?" stays answerable during an incident.
    revoked_at            timestamptz
);

create index if not exists idx_creator_portal_sessions_identity
    on identity.creator_portal_sessions (creator_identity_id);

-- The expiry sweep, and the reason this index is partial: expired rows are the ones being deleted,
-- so the index only needs to find them among the live ones.
create index if not exists idx_creator_portal_sessions_expiry
    on identity.creator_portal_sessions (expires_at)
    where revoked_at is null;

comment on table identity.creator_portal_sessions is
    'Server-side creator portal sessions (PR-40). Opaque tokens rather than JWTs, deliberately: '
    'the session is re-read on every call so revoking a link takes effect immediately instead of '
    'at token expiry. Stored as a sha256 hash — the token is a bearer credential and a database '
    'read must not yield live sessions.';

commit;

-- Rollback, if this ever has to come out. Note the order: drop the sessions table LAST, because
-- dropping it signs out every creator, and there is no reason to do that while diagnosing a
-- problem with the turn columns.
--   alter table content.landing_templates drop column if exists turn;
--   alter table content.landing_templates drop column if exists turn_changed_at;
--   drop table if exists content.page_handoffs;
--   drop table if exists identity.creator_portal_sessions;
