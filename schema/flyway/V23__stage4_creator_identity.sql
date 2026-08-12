-- =============================================================
-- Stage 4: creator identity
-- Date: 2026-08-02
-- Roadmap: docs/identity-signup-alignment.md
--
-- Purpose:
--   Let a creator sign in and see their own collaborations, across every brand that works
--   with them.
--
-- The modelling problem this solves:
--   A creator is stored as N per-brand rows by design — rates, safety notes and scores are
--   *relationship* data, so one agency client never sees another's negotiated terms. That
--   decision is deliberate and is not being reversed here. It does mean one creator login
--   must fan out to many creator.id values.
--
--   Tenancy therefore runs the opposite way to everything else in the platform. Every existing
--   rule is "this brand owns these rows". A creator's is "these brands have a row for me" —
--   the inverse. Modelling that as another account_role would be wrong: a creator is not a
--   member of a brand's account and must never inherit brand-scoped permissions.
--
-- Why claims are explicit rather than matched on email:
--   Only 48 of 210 creator rows carry an email at all, and handles repeat across brands
--   (@solo_demo appears under 23). Auto-linking on either would be guesswork, and guessing
--   wrong hands one creator another creator's rates. A link is created only when a brand
--   invites a specific creator row, or a creator claims one and a brand approves.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. A creator's login.
-- -------------------------------------------------------------
-- Separate from identity.users on purpose. A users row is a workspace operator: it resolves
-- through memberships to an account and carries account_role permissions. A creator has
-- neither, and giving them a users row would mean every membership query has to remember to
-- exclude them — a rule that will eventually be forgotten in one place and leak access.
create table if not exists identity.creator_identities (
    id            uuid primary key default gen_random_uuid(),
    email         citext not null unique,
    password_hash text,
    display_name  text,
    status        text not null default 'active' check (status in ('active', 'suspended')),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

comment on table identity.creator_identities is
    'A creator''s login. Deliberately NOT identity.users: a creator is not a member of any '
    'account and must not resolve through memberships to account_role permissions.';

-- -------------------------------------------------------------
-- 2. Which brand-side creator rows a login speaks for.
-- -------------------------------------------------------------
-- This is the fan-out: one identity, many creator rows, one per brand that works with them.
create table if not exists identity.creator_identity_links (
    id                   uuid primary key default gen_random_uuid(),
    creator_identity_id  uuid not null references identity.creator_identities(id) on delete cascade,
    -- No FK: creator.creators lives in another bounded context and Phase 5 severed all
    -- cross-context foreign keys. Referential integrity is the application's job here, the
    -- same trade already made for every other cross-context reference.
    creator_id           uuid not null,
    brand_id             uuid not null,
    -- 'claimed'  — the creator asserted this is them; the brand has not confirmed.
    -- 'confirmed'— a brand invited them, or approved a claim. Only this grants visibility.
    -- 'rejected' — the brand said no.
    status               text not null default 'claimed'
                         check (status in ('claimed', 'confirmed', 'rejected')),
    confirmed_by_user_id uuid references identity.users(id) on delete set null,
    confirmed_at         timestamptz,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

-- One link per (identity, creator row). Two would make "is this creator me?" ambiguous.
create unique index if not exists uq_creator_identity_links
    on identity.creator_identity_links (creator_identity_id, creator_id);

-- A creator row belongs to at most one identity. Without this, two logins could both claim
-- the same row and each would see the other's brand relationship.
create unique index if not exists uq_creator_identity_links_creator
    on identity.creator_identity_links (creator_id)
    where status = 'confirmed';

create index if not exists idx_creator_identity_links_identity
    on identity.creator_identity_links (creator_identity_id, status);

create index if not exists idx_creator_identity_links_brand
    on identity.creator_identity_links (brand_id, status);

comment on table identity.creator_identity_links is
    'Maps one creator login to the per-brand creator rows it speaks for. A creator exists as '
    'N rows by design, so identity fans out. Only status=confirmed grants visibility — a '
    'claim alone must not expose a brand''s negotiated terms.';

comment on column identity.creator_identity_links.status is
    'claimed = creator asserted it, unverified. confirmed = a brand invited or approved. '
    'rejected = refused. Email and handle are not sufficient evidence: most creator rows '
    'have no email and handles repeat across brands.';

commit;

-- ---------------------------------------------------------------
-- Rollback:
--   drop table if exists identity.creator_identity_links;
--   drop table if exists identity.creator_identities;
-- Both are additive; nothing in the brand-facing schema references them, so dropping them
-- returns the platform to its pre-Stage-4 behaviour with no data loss on the brand side.
-- ---------------------------------------------------------------
