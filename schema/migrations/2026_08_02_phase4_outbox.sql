-- =============================================================
-- Migration: Transactional outbox  (DDD Phase 4)
-- Date: 2026-08-02
-- Purpose:
--   Give contexts a way to publish domain events without calling each other
--   synchronously, and without needing a message broker yet.
--
--   Today the attribution -> commission -> payout chain is orchestrated by direct
--   calls. That works, but it couples the contexts at runtime: Finance cannot be
--   extracted (Phase 5) while Attribution reaches into it in-process.
--
-- Why an outbox rather than publishing straight to a broker:
--   Writing the event in the SAME transaction as the state change is what makes
--   "the row changed but the event was lost" impossible. A publisher then relays
--   rows to whatever transport exists. Introducing Kafka/Rabbit later becomes a
--   change of relay, not a change to any context.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

create table if not exists public.domain_events (
    id             uuid primary key default gen_random_uuid(),

    -- Which context emitted this, and about which aggregate. Kept as plain text +
    -- uuid rather than FKs: an event must remain readable after the aggregate it
    -- describes is deleted, and must not couple the outbox to every table.
    context        text not null,
    aggregate_type text not null,
    aggregate_id   uuid,

    -- Tenancy travels with the event so a consumer never has to guess which brand
    -- an event belongs to, and so the outbox itself can be filtered per tenant.
    brand_id       uuid,

    event_type     text not null,
    payload        jsonb not null default '{}'::jsonb,

    -- Lifecycle: pending -> published, or failed after repeated relay errors.
    status         text not null default 'pending'
                   check (status in ('pending','published','failed')),
    attempts       int  not null default 0,
    last_error     text,

    occurred_at    timestamptz not null default now(),
    published_at   timestamptz
);

comment on table domain_events is
    'Transactional outbox. Events are written in the same transaction as the state '
    'change that produced them, then relayed. Phase 4 relays in-process; Phase 5 may '
    'relay to a broker without any context changing.';

-- The relay polls for pending work in emission order. Partial index: published rows
-- are the overwhelming majority over time and must not slow the poll down.
create index if not exists idx_domain_events_pending
    on domain_events (occurred_at)
    where status = 'pending';

create index if not exists idx_domain_events_brand on domain_events (brand_id);
create index if not exists idx_domain_events_type  on domain_events (event_type);
create index if not exists idx_domain_events_aggregate
    on domain_events (aggregate_type, aggregate_id);

-- =============================================================
-- Post-conditions
-- =============================================================
do $$
begin
    -- NEITHER CHECK FILTERS ON `public`. This migration creates domain_events there, but the later
    -- phase-5 schema-per-context migration moves it to the `shared` schema. On a re-run a public-only
    -- lookup finds nothing and aborts the deploy claiming the table was never created — when it exists,
    -- one schema over.
    if not exists (select 1 from information_schema.tables
                    where table_name = 'domain_events') then
        raise exception 'Phase 4: domain_events was not created';
    end if;

    if not exists (select 1 from pg_indexes
                    where indexname = 'idx_domain_events_pending') then
        raise exception 'Phase 4: the pending-events index is missing; the relay would table-scan';
    end if;

    raise notice 'Phase 4 outbox ready: contexts can publish domain events transactionally.';
end $$;
