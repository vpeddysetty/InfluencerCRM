-- =============================================================
-- M2.1 / M2.2: subscriptions and billing
-- Date: 2026-08-07
-- Roadmap: EXECUTION-ROADMAP.md §2.1-2.2, PENDING-WORK-ROADMAP.md
--
-- M2.3 made accounts.plan mean something (limits are enforced). This is how an account gets onto
-- a plan other than 'free' and how that is billed.
--
-- WHY A SEPARATE TABLE RATHER THAN COLUMNS ON accounts. A subscription has a lifecycle of its own
-- — trialling, active, paused, past_due, cancelled — with dates that outlive any one plan value,
-- and an account keeps its billing history across upgrades. accounts.plan stays as the single
-- fast answer to "what may this account do right now", which is what PlanPolicy reads on every
-- entitlement check; this table is the record of WHY it says that.
--
-- THE PLAN COLUMN IS DELIBERATELY DUPLICATED. subscriptions.plan is the billed plan;
-- accounts.plan is the effective one. They differ on purpose during a pause: a paused
-- subscription still records plan='pro' (that is what resumes) while the account drops to 'free'
-- (that is what is enforced). Collapsing them would make pause either lose the plan or keep
-- granting paid limits for free.
--
-- NO CARD DATA, EVER. No PAN, no CVV, no expiry, no raw payment method. Only a provider's opaque
-- reference. The same reasoning as the domain registrar holding no private keys: the correct
-- answer for every provider involves storing none of it, so a schema shaped to hold it would be
-- wrong for all of them and a PCI liability besides.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- 1. The subscription.
-- -------------------------------------------------------------
create table if not exists identity.subscriptions (
    id                    uuid primary key default gen_random_uuid(),

    -- One live subscription per account; the partial unique index below enforces it. The account
    -- is the paying entity (see the comment on accounts), so this is deliberately not per-brand:
    -- an agency with three brands is one customer with one bill.
    account_id            uuid not null references identity.accounts(id) on delete cascade,

    -- The plan being BILLED. May differ from accounts.plan while paused - see the header.
    plan                  text not null,

    status                text not null default 'trialing'
        check (status in ('trialing','active','paused','past_due','cancelled')),

    -- Which adapter owns this subscription: 'manual' today, 'stripe' when keys exist. Recorded
    -- rather than assumed, the same way metrics_source and the registrar's provider are, so a
    -- subscription created without a payment provider can never be mistaken for a paid one.
    provider              text not null default 'manual',

    -- The provider's opaque id. Nullable because a manual subscription has none.
    provider_ref          text,

    -- Billing period. current_period_end is what a renewal moves and what "your plan runs until"
    -- reads from; NULL means no period has started (a manual or trialing subscription).
    current_period_start  timestamptz,
    current_period_end    timestamptz,

    -- Set when a cancel is requested. The subscription stays ACTIVE until current_period_end -
    -- cancelling should not confiscate time already paid for, and a customer who cancels on day 2
    -- of a month keeps the other 28 days. This column is what makes that difference visible.
    cancel_at_period_end  boolean not null default false,
    cancelled_at          timestamptz,

    -- Pause is reversible and keeps the row. paused_at is kept so "paused since March" is
    -- answerable without reading an event log.
    paused_at             timestamptz,

    trial_ends_at         timestamptz,

    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

comment on table identity.subscriptions is
    'What an account is paying for and the state of that arrangement. accounts.plan is the '
    'effective plan enforced by PlanPolicy; this is the billed plan and the reason behind it. '
    'They diverge during a pause, which is intentional.';

comment on column identity.subscriptions.plan is
    'The BILLED plan. During a pause this keeps the paid plan (what resumes) while accounts.plan '
    'drops to free (what is enforced).';

comment on column identity.subscriptions.cancel_at_period_end is
    'A cancel request that has not taken effect yet. The subscription stays active until '
    'current_period_end, because cancelling must not confiscate time already paid for. This is '
    'also what makes "no lock-in" checkable: the user sees exactly when access ends.';

comment on column identity.subscriptions.provider_ref is
    'The payment provider''s opaque id. NEVER card data - no PAN, CVV, expiry or raw payment '
    'method is stored anywhere in this schema.';

-- At most one subscription per account that is not finished. Partial rather than a plain unique
-- constraint so a cancelled subscription can stay as history while a new one is created - without
-- this, resubscribing after a cancel would collide with the old row.
create unique index if not exists idx_subscriptions_one_live_per_account
    on identity.subscriptions (account_id)
    where status <> 'cancelled';

create index if not exists idx_subscriptions_account
    on identity.subscriptions (account_id);

-- Renewal and expiry sweeps read by period end. Partial: cancelled rows are never swept.
create index if not exists idx_subscriptions_period_end
    on identity.subscriptions (current_period_end)
    where status <> 'cancelled' and current_period_end is not null;

-- -------------------------------------------------------------
-- 2. Invoices - what was actually charged.
-- -------------------------------------------------------------
create table if not exists identity.billing_invoices (
    id               uuid primary key default gen_random_uuid(),

    account_id       uuid not null references identity.accounts(id) on delete cascade,
    subscription_id  uuid references identity.subscriptions(id) on delete set null,

    -- Kept on delete set null rather than cascade: an invoice is a financial record and must
    -- outlive the subscription it came from. Deleting billing history because someone resubscribed
    -- would destroy the only answer to "what did we pay last year".
    amount_cents     bigint not null,
    currency         text not null default 'USD',

    status           text not null default 'draft'
        check (status in ('draft','open','paid','void','uncollectible')),

    provider         text not null default 'manual',
    provider_ref     text,

    period_start     timestamptz,
    period_end       timestamptz,
    issued_at        timestamptz,
    paid_at          timestamptz,

    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

comment on table identity.billing_invoices is
    'What was charged, when, and whether it was paid. Survives its subscription on purpose - '
    'billing history must outlive the arrangement that produced it.';

comment on column identity.billing_invoices.amount_cents is
    'Minor units as an integer. Never a float: 79.99 is not representable in binary floating '
    'point, and money that does not sum exactly is a reconciliation bug that surfaces months later.';

create index if not exists idx_billing_invoices_account
    on identity.billing_invoices (account_id, created_at desc);

-- A provider's invoice id is unique per provider. This is what makes webhook replay safe: the
-- same event delivered twice cannot create a second invoice row.
create unique index if not exists idx_billing_invoices_provider_ref
    on identity.billing_invoices (provider, provider_ref)
    where provider_ref is not null;

-- -------------------------------------------------------------
-- 3. Webhook events - the replay guard.
-- -------------------------------------------------------------
-- The roadmap's own requirement for 2.2: "Subscription state must survive a webhook replay."
-- Providers deliver at-least-once and retry on any non-2xx, so the same event WILL arrive twice.
-- Recording the id before acting makes handling idempotent without the handler having to reason
-- about it.
create table if not exists identity.billing_events (
    id             uuid primary key default gen_random_uuid(),
    provider       text not null,
    event_id       text not null,
    event_type     text not null,
    payload        jsonb,
    processed_at   timestamptz,
    created_at     timestamptz not null default now()
);

comment on table identity.billing_events is
    'Every billing webhook seen, by provider event id. Exists so a replayed event is recognised '
    'and skipped - providers deliver at-least-once, so a duplicate is expected, not exceptional.';

create unique index if not exists idx_billing_events_provider_event
    on identity.billing_events (provider, event_id);

commit;

-- -------------------------------------------------------------
-- Rollback
-- -------------------------------------------------------------
-- Additive: nothing outside these three tables changed, and accounts.plan is untouched, so
-- dropping them returns the system to M2.3 behaviour (limits still enforced, no way to subscribe).
--
--   begin;
--   drop table if exists identity.billing_events;
--   drop table if exists identity.billing_invoices;
--   drop table if exists identity.subscriptions;
--   commit;
