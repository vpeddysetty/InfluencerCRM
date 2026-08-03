-- =============================================================
-- Stage 3: member invitations
-- Date: 2026-08-02
-- Roadmap: docs/identity-signup-alignment.md
--
-- Purpose:
--   Let an account owner add a member without raw SQL. The permissions for this
--   (member:invite, member:update, member:remove) have existed since the RBAC phase
--   and are already granted to OWNER and ADMIN — only the storage and the endpoints
--   were missing.
--
--   Today the only way to put a user on someone else's account is what
--   schema/seed/test_accounts.sql does: sign the user up, DELETE the solo account the
--   signup created, and re-parent them. That is not a capability, it is a workaround,
--   and it cannot be offered to a customer.
--
-- Why an invitation is a row and not just a membership:
--   The invitee may not have an account yet, so there is no user_id to hang a
--   membership on. The invitation records intent — "this email may join this account
--   with this role" — and becomes a membership only when it is accepted. It also gives
--   the owner something to revoke before it is used.
--
-- Token handling:
--   Only a SHA-256 hash of the token is stored, never the token itself. An invitation
--   token is a credential: it grants access to an account. A leaked database dump must
--   not hand over working invitations, for the same reason it must not hand over
--   passwords.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

begin;

create table if not exists identity.member_invitations (
    id              uuid primary key default gen_random_uuid(),
    account_id      uuid not null references identity.accounts(id) on delete cascade,
    email           citext not null,
    role            account_role not null,
    -- Null means account-wide (every brand). A scoped invitation names one brand, which is
    -- how an agency gives a marketer access to one client without exposing the others.
    brand_id        uuid references identity.brands(id) on delete cascade,
    token_hash      text not null,
    status          text not null default 'pending'
                    check (status in ('pending', 'accepted', 'revoked', 'expired')),
    invited_by      uuid references identity.users(id) on delete set null,
    accepted_by     uuid references identity.users(id) on delete set null,
    expires_at      timestamptz not null,
    accepted_at     timestamptz,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

-- Lookup on redemption is by token hash, and it must be unique: two invitations sharing a
-- hash would make "which account am I joining?" ambiguous.
create unique index if not exists uq_member_invitations_token
    on identity.member_invitations (token_hash);

-- One live invitation per (account, email). Without this, clicking "invite" twice leaves two
-- valid tokens, and revoking the visible one still lets the other in.
create unique index if not exists uq_member_invitations_pending
    on identity.member_invitations (account_id, email)
    where status = 'pending';

create index if not exists idx_member_invitations_account
    on identity.member_invitations (account_id, status);

comment on table identity.member_invitations is
    'Pending offers to join an account. Becomes a membership on acceptance. Stores only a '
    'hash of the token, never the token, because the token is a credential that grants '
    'access to the account.';

comment on column identity.member_invitations.brand_id is
    'Null = account-wide access. Set = access to this brand only, which is how an agency '
    'scopes a member to one client brand.';

commit;

-- ---------------------------------------------------------------
-- Rollback:
--   drop table if exists identity.member_invitations;
-- Dropping loses pending invitations; accepted ones are already memberships and survive.
-- ---------------------------------------------------------------
