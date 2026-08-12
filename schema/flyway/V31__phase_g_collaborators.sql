-- =============================================================
-- Phase G: brand-creator co-editing
-- Date: 2026-08-06
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase G, §6.1
--
-- What this is and is not:
--   A creator may be granted edit access to a specific brand-owned page. A creator can NEVER
--   create a page of their own (decision #1, §6.1) — every landing page is owned by a brand,
--   full stop. That is why this table has no ownership column: it grants access to a page that
--   already belongs to someone.
--
-- Access is a NARROWING of an existing relationship, not a new grant:
--   A creator may be invited to co-edit a page only if they hold a CONFIRMED
--   identity.creator_identity_links row against that page's brand. The brand already approved
--   that link, so page access adds nothing they had not already agreed to. Revoking the
--   identity link revokes page access with it — one place to cut off a creator, not two.
--
-- Publishing is not a collaborator right (§6.1):
--   `rights` is comment|edit. There is no publish value and the schema does not anticipate one.
--   A collaborator may shape a page; releasing it to a domain or a social account requires
--   content:publish, which only account members hold. A creator cannot publish to a brand's
--   domain or accounts.
--
-- Simultaneous editing is NOT solved here (G.6, deferred). Two people editing at different
-- times is a different problem from two people editing at the same instant, and version
-- history (A.5) already makes the former safe by making overwrites recoverable.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

create table if not exists content.landing_page_collaborators (
    id                  uuid primary key default gen_random_uuid(),

    landing_template_id uuid not null,

    -- Denormalized so a collaborator list can be tenant-filtered without joining back to a
    -- page that may since have been deleted.
    brand_id            uuid not null,

    -- The creator's PORTAL identity, not their creator.creators row. A creator has one login
    -- and N per-brand creator rows; access belongs to the person, not to one of those rows.
    creator_identity_id uuid not null,

    -- comment | edit. Deliberately no 'publish' — see the header.
    rights              text not null default 'edit',

    granted_by_user_id  uuid,
    granted_at          timestamptz not null default now(),

    -- Revoked in place rather than deleted, so the record of who had access and when survives.
    revoked_at          timestamptz,
    revoked_by_user_id  uuid
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'ck_collaborators_rights') then
        -- The constraint IS the policy. Adding 'publish' is a migration and a decision.
        alter table content.landing_page_collaborators add constraint ck_collaborators_rights
            check (rights in ('comment','edit'));
    end if;
    -- One ACTIVE grant per (page, identity). Re-inviting someone already active is a no-op
    -- rather than a duplicate row, but a revoked grant can be re-issued later.
    if not exists (select 1 from pg_indexes where indexname = 'uq_collaborators_active') then
        create unique index uq_collaborators_active
            on content.landing_page_collaborators (landing_template_id, creator_identity_id)
            where revoked_at is null;
    end if;
end $$;

create index if not exists idx_collaborators_identity
    on content.landing_page_collaborators (creator_identity_id)
    where revoked_at is null;

create index if not exists idx_collaborators_template
    on content.landing_page_collaborators (landing_template_id);

comment on table content.landing_page_collaborators is
    'Grants a creator identity edit or comment access to ONE brand-owned page (roadmap G.1). '
    'Requires a confirmed creator_identity_links row for that brand — page access is a '
    'narrowing of a relationship the brand already approved. Publishing is never granted here.';
comment on column content.landing_page_collaborators.rights is
    'comment | edit. Never publish: releasing a page to a domain or a social account requires '
    'content:publish, which only account members hold (roadmap §6.1).';

commit;

-- ---------------------------------------------------------------
-- Verification (expect 0):
--   select count(*) from content.landing_page_collaborators;
--
-- Rollback:
--   drop table if exists content.landing_page_collaborators;
--   -- Additive; no other phase depends on it. Dropping removes co-editing access but leaves
--   -- every page and its version history untouched, since pages are brand-owned regardless.
-- ---------------------------------------------------------------
