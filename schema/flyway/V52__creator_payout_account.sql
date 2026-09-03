-- Stripe Connect onboarding state on the creator record (roadmap PR-47).
--
-- WHY ON THE CREATOR AND NOT A SEPARATE TABLE. A creator has exactly one payout account per brand
-- relationship, and `creator.creators` is already one row per (creator, brand) -- so a side table
-- would have the same cardinality as the row it hangs off, joined on every read, to hold two
-- columns. When a second payout rail lands (PR-50, PayPal), the shape to reach for is a
-- provider-keyed table; it is not needed for one.
--
-- TWO COLUMNS, AND THE SECOND ONE IS THE IMPORTANT ONE. `stripe_account_id` says an account exists.
-- `payouts_enabled` says Stripe will actually move money to it -- and those are days apart in
-- practice, because identity verification, the bank account and the tax form each gate it
-- separately. A brand looking at a creator needs the second answer, not the first: "onboarding
-- started" and "can be paid" are different facts and conflating them is how someone promises a
-- payout date they cannot keep.
--
-- NO TAX-FORM COLUMN HERE, deliberately. Stripe Connect Express collects W-9/W-8BEN on its own
-- hosted pages and reports the outcome through `payouts_enabled` and the requirements object; a
-- column here would be a second copy of a fact Stripe owns, and a stale copy of a tax status is
-- worse than no copy. PR-49 is where tax collection becomes a first-class concern, and section 11.5
-- carries the warning that matters: a US creator cannot be paid more than $600 in a year without
-- the form on file.
--
-- NULLABLE, both. Most creators will never have a Connect account: the manual payout provider is
-- the shipped default, and a brand paying by bank transfer never triggers onboarding at all.

begin;

alter table creator.creators
    add column if not exists stripe_account_id text,
    add column if not exists payouts_enabled boolean not null default false,
    -- When Stripe last told us. Not "when we asked": the value is only as fresh as the last
    -- webhook or explicit refresh, and a brand reading `payouts_enabled` deserves to know whether
    -- that answer is minutes or weeks old before they promise anyone a date.
    add column if not exists payout_status_checked_at timestamptz;

comment on column creator.creators.stripe_account_id is
    'Stripe Connect Express account. Its EXISTENCE means onboarding started, not that anyone can be '
    'paid -- read payouts_enabled for that.';

comment on column creator.creators.payouts_enabled is
    'Stripe will move money to this account. False until identity, bank and tax all clear, which is '
    'usually days after the account is created.';

-- One Stripe account per row, and never two rows claiming the same one: a duplicate would mean two
-- creator records paying into one account with no way to say which was intended. Partial, because
-- the overwhelming majority of rows are NULL and a plain unique index would reject the second one.
create unique index if not exists uq_creators_stripe_account
    on creator.creators (stripe_account_id)
    where stripe_account_id is not null;

commit;
