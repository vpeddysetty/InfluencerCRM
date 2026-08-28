-- =============================================================
-- PR-41: tokenised creator invitations.
-- Date: 2026-08-27
-- Design: docs/Creator-Handoff-Design.md §3 step 2
--
-- WHAT THIS UNBLOCKS
--
--   The collaboration backend has been dark since Phase G, and not because it is unfinished:
--   PageCollaborationService.invite REFUSES unless the creator already holds a *confirmed*
--   creator_identity_links row against that brand. The only way to reach `confirmed` today is for
--   a creator to sign up at the portal, claim a brand by guessing at it, and for the brand to
--   approve the claim -- which requires the two of them to exchange a UUID out of band first.
--
--   That is the bootstrap circularity: a brand cannot invite a creator to a page until the creator
--   is confirmed, and a creator cannot become confirmed without the brand already knowing them.
--   This table breaks it. The brand sends an invitation to an EMAIL ADDRESS, and redeeming it
--   creates the identity and the confirmed link together, in one transaction.
--
-- MODELLED ON identity.member_invitations (V22), DELIBERATELY
--
--   Same token discipline, same status vocabulary, same partial unique index. The two tables are
--   not merged because the things they grant are different in kind -- a member invitation grants
--   access to an ACCOUNT and becomes a membership with a role; this grants a working relationship
--   with ONE BRAND and becomes a creator_identity_links row with no role at all. Merging them
--   would mean a nullable role column whose meaning depended on another column, which is how a
--   check constraint becomes unwritable.
--
-- WHY ONLY A HASH IS STORED
--
--   The token IS the credential -- whoever holds it can become a confirmed collaborator on the
--   brand's unpublished pages. A leaked database dump, a backup, or an errant query must not hand
--   over working invitations, for the same reason it must not hand over passwords.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

create table if not exists identity.creator_invites (
    id                  uuid primary key default gen_random_uuid(),
    brand_id            uuid not null,
    email               citext not null,
    -- The creator row this invitation is about, when the brand is inviting somebody already in
    -- their CRM. Null when inviting a stranger by email alone, which is the cold-start case.
    creator_id          uuid,
    -- Optional: the page the brand wants help with. Null for a plain "come and work with us".
    -- Not a foreign key to landing_templates: that table lives in the content schema and this one
    -- in identity, and a cross-schema FK would couple two contexts that are meant to be separable.
    landing_template_id uuid,
    token_hash          text not null,
    status              text not null default 'pending'
                        check (status in ('pending', 'accepted', 'revoked', 'expired')),
    invited_by_user_id  uuid,
    -- The identity that redeemed it. Null until then, and kept afterwards so "who actually took
    -- this invitation?" stays answerable when an address was forwarded.
    accepted_by_creator_identity_id uuid,
    expires_at          timestamptz not null,
    accepted_at         timestamptz,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

-- Redemption looks the invitation up BY HASH, so the hash must be unique: two invitations sharing
-- one would make "which brand am I joining?" ambiguous at the moment it matters most.
create unique index if not exists uq_creator_invites_token
    on identity.creator_invites (token_hash);

-- One live invitation per (brand, email). Without this, clicking "invite" twice leaves two valid
-- tokens, and revoking the one the UI shows still lets the other in -- a revocation that looks
-- like it worked and did not. V22 records the same trap for member invitations.
create unique index if not exists uq_creator_invites_pending
    on identity.creator_invites (brand_id, email)
    where status = 'pending';

create index if not exists idx_creator_invites_brand
    on identity.creator_invites (brand_id, status);

comment on table identity.creator_invites is
    'Pending invitations for a creator to work with a brand (PR-41). Redeeming one creates a '
    'creator identity and a CONFIRMED creator_identity_links row together, which is what breaks '
    'the bootstrap circularity: before this, the only route to a confirmed link was an '
    'out-of-band UUID exchange, and PageCollaborationService.invite refuses without one.';

comment on column identity.creator_invites.token_hash is
    'SHA-256 of the invitation token. The token itself is shown once, in the email, and never '
    'stored -- it is a credential that grants collaborator access to unpublished pages.';

commit;

-- Rollback:
--   drop table if exists identity.creator_invites;
