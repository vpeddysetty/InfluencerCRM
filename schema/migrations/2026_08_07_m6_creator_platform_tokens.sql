-- =============================================================
-- M6.3: creator platform OAuth tokens
-- Date: 2026-08-07
-- Roadmap: EXECUTION-ROADMAP.md M6.3 / PENDING-WORK-ROADMAP.md item 2
--
-- No schema existed for this. The OAuth config in application.properties covers Google and
-- Facebook *login* — an operator signing in to this platform — which is a different thing from a
-- creator granting us read access to their own audience metrics. Login proves who is at the
-- keyboard; this proves a creator agreed we may read their numbers.
--
-- Three decisions this schema encodes:
--
-- 1. TOKENS ARE NOT STORED IN PLAINTEXT, AND THIS MIGRATION DOES NOT PRETEND OTHERWISE.
--    `access_token_encrypted` and `refresh_token_encrypted` are named for what must go in them.
--    The envelope-encryption helper is M3.1, which is scheduled and not yet built — the same
--    helper marketplace credentials need, and the roadmap is explicit that it must land BEFORE
--    real credentials flow, not after. Until it exists nothing writes here: the adapters that
--    would populate this table are simulated, and the one real adapter (YouTube) uses a server
--    API key from config and needs no per-creator token at all.
--
--    The column names are the contract. A future writer that puts a raw token in a column called
--    `_encrypted` is doing something visibly wrong, which is the point of naming them this way
--    before there is anything to write.
--
-- 2. ONE ROW PER (CREATOR, PLATFORM), NOT ONE PER CREATOR.
--    A creator can connect Instagram and TikTok independently, revoke one without the other, and
--    reconnect either after a token expires. A single token column on `creators` would force a
--    revoke on one platform to look like a revoke on all of them.
--
-- 3. brand_id IS CARRIED, AND IS NOT A FOREIGN KEY.
--    Every tenancy-scoped read in this system filters on brand_id, and cross-context foreign keys
--    were deliberately severed in phase 5 (see 2026_08_02_phase5_sever_all_cross_context_fks.sql).
--    Carrying the column keeps the row filterable by the same rule as every other creator row
--    without reintroducing a cross-schema dependency.
-- =============================================================

CREATE TABLE IF NOT EXISTS creator.creator_platform_tokens (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id                uuid NOT NULL,
    creator_id              uuid NOT NULL,

    -- instagram | tiktok | youtube. Text rather than an enum: the adapter registry is open by
    -- design, and adding a platform should not need a migration to widen a type.
    platform                text NOT NULL,

    -- The platform's own id for the account, captured at grant time. A creator can change their
    -- handle; this is what survives it, and is how a reconnect is matched to the same account.
    platform_account_id     text,
    platform_handle         text,

    -- See decision 1. Nullable because a row may exist in `revoked` state with the secrets
    -- already cleared — the record that access WAS granted outlives the credential.
    access_token_encrypted  text,
    refresh_token_encrypted text,

    -- When the access token dies. Nullable: not every platform issues an expiring token, and a
    -- null here means "unknown", never "never expires".
    expires_at              timestamptz,

    -- Space-separated OAuth scopes actually granted, which is not always what was requested.
    -- Stored so a failed read can be explained ("they did not grant insights") rather than
    -- retried forever.
    granted_scopes          text,

    -- pending | active | expired | revoked. `revoked` is terminal and keeps the row: deleting it
    -- would lose the fact that a creator once granted and then withdrew access, which is exactly
    -- what someone asks about when metrics stop updating.
    status                  text NOT NULL DEFAULT 'pending',

    -- Provenance, matching the `metrics_source` vocabulary already on `creators`. A token row
    -- created by the simulated adapters says so.
    source                  text NOT NULL DEFAULT 'mock',

    last_refreshed_at       timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT creator_platform_tokens_status_check
        CHECK (status IN ('pending', 'active', 'expired', 'revoked')),

    -- Decision 2: one connection per creator per platform. A reconnect updates in place rather
    -- than accumulating rows nobody can choose between.
    CONSTRAINT creator_platform_tokens_creator_platform_key
        UNIQUE (creator_id, platform)
);

-- The tenancy filter every read uses.
CREATE INDEX IF NOT EXISTS idx_creator_platform_tokens_brand
    ON creator.creator_platform_tokens (brand_id);

-- The refresh scheduler's query (M6.6): "which active tokens expire soon?". Partial, because
-- revoked and pending rows are never candidates and there is no reason to index them.
CREATE INDEX IF NOT EXISTS idx_creator_platform_tokens_expiring
    ON creator.creator_platform_tokens (expires_at)
    WHERE status = 'active';

COMMENT ON TABLE creator.creator_platform_tokens IS
    'Per-creator, per-platform OAuth grants for reading audience metrics (M6.3). Distinct from the '
    'Google/Facebook login OAuth, which authenticates an operator rather than authorising a read. '
    'Token columns stay NULL until the M3.1 envelope-encryption helper exists.';

COMMENT ON COLUMN creator.creator_platform_tokens.access_token_encrypted IS
    'Envelope-encrypted access token. NEVER a raw token — the column name is the contract.';

COMMENT ON COLUMN creator.creator_platform_tokens.status IS
    'pending | active | expired | revoked. revoked is terminal and retains the row.';
