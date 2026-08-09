-- =============================================================
-- Migration: Federated identities  (social sign-in / agency SSO foundation)
-- Date: 2026-08-02
-- Purpose:
--   Let an external identity provider assert who a user is, instead of the user
--   proving it with a password we store.
--
--   Today identity.users.password_hash is NOT NULL, which encodes the assumption
--   that every user authenticates with a password. A Google-only signup has no
--   password to hash. The wrong fix is to store a random string there: it looks
--   like a credential, can never be used as one, and hides the fact that the
--   account has exactly one way in.
--
-- Scope:
--   This migration is additive and reversible. It creates the mapping table and
--   relaxes one NOT NULL. It changes no existing row and no live auth path —
--   password login behaves exactly as before.
--
-- What this table is NOT:
--   Not a creator's connected social account. That lives in the Creator context
--   and carries long-lived API access tokens for follower sync. This table stores
--   only a subject id proving *who signed in*. Keeping them apart is why revoking
--   Instagram data access cannot lock a creator out of their account.
--
-- Lives in the identity schema:
--   Authentication belongs to the Identity context, so this table travels with it.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- -------------------------------------------------------------
-- 1. A federated user may have no password.
-- -------------------------------------------------------------
-- Dropping NOT NULL cannot invalidate an existing row: every current user has a
-- hash, and they keep it. This only permits rows that never had one.
alter table identity.users
    alter column password_hash drop not null;

comment on column identity.users.password_hash is
    'Null for users who authenticate only through an external provider. The '
    '"at least one usable credential" rule is enforced in the User aggregate, not here — '
    'a check constraint cannot see the federated_identities rows it would depend on.';

-- -------------------------------------------------------------
-- 2. The mapping from an external assertion to a local user.
-- -------------------------------------------------------------
create table if not exists identity.federated_identities (
    id             uuid primary key default gen_random_uuid(),

    user_id        uuid not null references identity.users(id) on delete cascade,

    -- 'google', 'facebook', 'instagram', 'tiktok', or 'saml:{accountId}' for an
    -- agency's own IdP. Scoped per account for SAML because two agencies both
    -- using Okta are two distinct trust relationships, not one.
    provider       text not null,

    -- The provider's opaque, stable subject id (OIDC 'sub', SAML NameID).
    -- Deliberately NOT the email: emails get reassigned when someone leaves an
    -- agency, and matching on them is the classic account-takeover route.
    subject        text not null,

    -- The email as asserted at link time. For display and audit only — never a
    -- matching key, which is why it carries no unique constraint.
    asserted_email text,

    -- Whether the provider claims to have verified that email. Stored rather than
    -- assumed: providers differ, and auto-linking an unverified assertion to an
    -- existing account hands that account to whoever registered the address.
    email_verified_by_idp boolean not null default false,

    linked_at      timestamptz not null default now(),
    last_authenticated_at timestamptz,

    -- One external account authenticates exactly one user. Without this, a second
    -- sign-in that failed to find its row would silently fork a duplicate account
    -- and the user would "lose" their data with no error anywhere.
    constraint uq_federated_identity_provider_subject unique (provider, subject)
);

comment on table identity.federated_identities is
    'Maps an external IdP assertion to a local user. Stores a subject id, never an access '
    'token — a creator''s connected social account lives in the Creator context and is a '
    'separate grant with a separate lifecycle.';

-- Login path: resolve (provider, subject) on every federated sign-in. Served by
-- the unique constraint's index; named explicitly so its purpose is greppable.
create index if not exists idx_federated_identities_provider_subject
    on identity.federated_identities (provider, subject);

-- "Which providers is this user linked to?" — rendered on the account settings
-- screen and read by the unlink rule before it allows removal.
create index if not exists idx_federated_identities_user
    on identity.federated_identities (user_id);

-- Revoking a compromised provider: every identity from it, in one scan.
create index if not exists idx_federated_identities_provider
    on identity.federated_identities (provider);

-- The Identity service reaches this table under its own role.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_identity') then
        execute 'grant select, insert, update, delete on identity.federated_identities to svc_identity';
    end if;
end $$;

-- =============================================================
-- Post-conditions
-- =============================================================
do $$
begin
    if not exists (select 1 from information_schema.tables
                    where table_schema = 'identity' and table_name = 'federated_identities') then
        raise exception 'federated_identities was not created';
    end if;

    -- The constraint that stops one external account becoming two local users.
    if not exists (select 1 from pg_constraint
                    where conname = 'uq_federated_identity_provider_subject') then
        raise exception 'the (provider, subject) unique constraint is missing — '
                        'federated sign-in must not run without it';
    end if;

    if (select is_nullable from information_schema.columns
         where table_schema = 'identity' and table_name = 'users'
           and column_name = 'password_hash') <> 'YES' then
        raise exception 'users.password_hash is still NOT NULL; federated users cannot be created';
    end if;

    raise notice 'Federated identities are ready. No existing user was modified and no auth '
                 'path changed: password login is unaffected until a provider is wired up.';
end $$;
