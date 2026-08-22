-- =============================================================
-- Email verification for password signups
-- Date: 2026-08-21
--
-- Purpose:
--   A password signup currently issues a session the instant the row is written, so
--   nobody has ever proved they control the address they typed. That address is what
--   password reset is sent to and what every notification goes to, which means a typo
--   silently creates an account its owner cannot recover, and a deliberate entry of
--   someone else's address creates one they never asked for.
--
-- Why federated signups are exempt, and are not merely "assumed fine":
--   identity.federated_identities.email_verified_by_idp already records whether the
--   provider asserted a verified address, and FederatedIdentity.isTrustworthy() already
--   refuses an unverified assertion. Google and Facebook have done this check. Asking a
--   user to confirm an address the IdP just confirmed adds a step and no security.
--
-- Token handling mirrors identity.member_invitations, deliberately:
--   Only a SHA-256 hash is stored, never the token. A verification token is a credential
--   - it turns a locked account into a usable one - so a leaked database dump must not
--   contain working ones, the same reason it must not contain passwords.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

begin;

-- Nullable, with NO default, and that is the point.
--
-- NULL means "this account predates verification" and is treated as verified by
-- EmailVerificationPolicy. A default of false would lock out every existing account the
-- moment this migration ran - the enforcement is on sign-in, so a backfill of false is
-- indistinguishable from a real unverified signup and there would be no way to tell them
-- apart afterwards.
--
-- New password signups write false explicitly. New federated signups write a timestamp.
alter table identity.users
    add column if not exists email_verified_at timestamptz;

comment on column identity.users.email_verified_at is
    'When the address was proven. NULL means the account predates verification and is '
    'grandfathered; a timestamp means proven, either by clicking a link or by an IdP '
    'assertion. Sign-in is refused only when a verification row exists and is unconsumed.';

create table if not exists identity.email_verifications (
    id              uuid primary key default gen_random_uuid(),
    user_id         uuid not null references identity.users(id) on delete cascade,

    -- The address the token was SENT to, recorded rather than read back from users.email.
    -- If someone changes their email while a token is outstanding, the old token must not
    -- verify the new address - that would let an attacker who controls the old inbox
    -- confirm an address they do not control.
    email           citext not null,

    -- SHA-256 of the token. See the header.
    token_hash      text not null unique,

    expires_at      timestamptz not null,

    -- Set when the link is clicked. Single-use: a consumed token must not verify twice,
    -- because a forwarded confirmation email would otherwise stay live indefinitely.
    consumed_at     timestamptz,

    -- How many times a fresh token has been posted to this address. Bounded so the
    -- endpoint cannot be used to send unlimited mail to an arbitrary address - the
    -- resend endpoint is unauthenticated by necessity (the user cannot sign in yet).
    send_count      integer not null default 1 check (send_count > 0),
    last_sent_at    timestamptz not null default now(),

    created_at      timestamptz not null default now()
);

-- One live verification per user. Partial, so consumed rows accumulate as history rather
-- than blocking a re-send: a user who let a token expire needs a new row, and a unique
-- index over all rows would refuse it.
create unique index if not exists uq_email_verifications_live
    on identity.email_verifications (user_id)
    where consumed_at is null;

create index if not exists ix_email_verifications_expires
    on identity.email_verifications (expires_at)
    where consumed_at is null;

comment on table identity.email_verifications is
    'Outstanding proof-of-address challenges for password signups. Federated signups have '
    'no row: the IdP already asserted the address (see federated_identities.'
    'email_verified_by_idp). Only a SHA-256 hash of the token is stored.';

commit;
