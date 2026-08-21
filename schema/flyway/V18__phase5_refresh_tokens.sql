-- =============================================================
-- Migration: Persistent refresh tokens  (Identity extraction prerequisite)
-- Date: 2026-08-02
-- Purpose:
--   Move refresh tokens out of an in-memory map and into Postgres.
--
--   RefreshTokenStore held tokens in a ConcurrentHashMap. That was survivable while
--   one process served everything: losing the map only forced users to log in again
--   at next refresh. It stops being survivable the moment a second instance exists,
--   because instance B cannot see a token issued by instance A — the user gets an
--   apparently random logout that reads as a session bug rather than a topology one.
--
--   Workflow already runs as a separate service, and Identity is next. This is a
--   prerequisite for that.
--
-- Only the hash is stored:
--   The raw token is handed to the client once and never persisted. A dump of this
--   table therefore cannot be replayed to impersonate anyone — the same reason
--   password_hash exists rather than password.
--
-- Lives in the identity schema:
--   Sessions belong to the Identity context. When Identity is extracted, this table
--   travels with it and needs no further migration.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

create table if not exists identity.refresh_tokens (
    id           uuid primary key default gen_random_uuid(),

    -- SHA-256 of the raw token. Unique so a replayed or duplicated token cannot
    -- silently create a second live session.
    token_hash   text not null unique,

    user_id      uuid not null references identity.users(id) on delete cascade,

    -- Which sign-in produced it (password, google, facebook, switch). Useful when a
    -- provider is compromised and every session from it must be revoked.
    provider     text,

    issued_at    timestamptz not null default now(),
    expires_at   timestamptz not null,

    -- Rotation-on-use: the row is deleted rather than flagged, so a replayed token
    -- simply finds nothing. Kept as a column for forensic use if a soft-delete policy
    -- is ever wanted.
    revoked_at   timestamptz
);

comment on table identity.refresh_tokens is
    'Revocable refresh tokens. Only the SHA-256 hash is stored, so this table cannot be '
    'replayed if dumped. Replaces the in-memory map, which a second instance could not see.';

-- Lookup on every refresh: by hash.
create unique index if not exists idx_refresh_tokens_hash
    on identity.refresh_tokens (token_hash);

-- "Log this user out everywhere" and per-user cleanup.
create index if not exists idx_refresh_tokens_user
    on identity.refresh_tokens (user_id);

-- Expiry sweep. Partial: only unexpired rows are ever scanned in the hot path.
create index if not exists idx_refresh_tokens_expiry
    on identity.refresh_tokens (expires_at);

-- The Identity service reaches this table under its own role once extracted.
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'svc_identity') then
        execute 'grant select, insert, update, delete on identity.refresh_tokens to svc_identity';
    end if;
end $$;

-- =============================================================
-- Post-conditions
-- =============================================================
do $$
begin
    if not exists (select 1 from information_schema.tables
                    where table_schema = 'identity' and table_name = 'refresh_tokens') then
        raise exception 'refresh_tokens was not created';
    end if;

    -- The unique constraint is what stops a duplicated token becoming two live sessions.
    if not exists (select 1 from pg_indexes
                    where schemaname = 'identity' and indexname = 'idx_refresh_tokens_hash') then
        raise exception 'the refresh-token hash index is missing';
    end if;

    raise notice 'Refresh tokens are now persistent: sessions survive restart and are visible '
                 'to every instance.';
end $$;
