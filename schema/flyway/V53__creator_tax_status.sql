-- Tax-form state and the 1099-NEC threshold (roadmap PR-49).
--
-- WHAT THIS DOES NOT DO, and the roadmap row is explicit: it does not generate 1099s. Stripe Connect
-- has tax reporting as a feature; enabling it is a setting, and building form generation would mean
-- owning IRS filing deadlines, correction workflows and per-state variation for a product with zero
-- subscribers. This table tracks the ONE fact that determines whether someone may be paid at all.
--
-- WHY A COLUMN HERE WHEN V52 REFUSED ONE. V52 declined to copy the tax status because Stripe owns it
-- and a stale copy is worse than none. That still holds for the STATUS. What is stored here is
-- different: `tax_form_required_at` is the moment THIS platform decided a form is needed, which is a
-- fact about our own threshold arithmetic and about our own decision to withhold payment -- not a
-- fact about Stripe. The distinction matters because the first is evidence of why a payout was held
-- and the second would be a cache.
--
-- THE THRESHOLD IS PER CREATOR PER BRAND PER CALENDAR YEAR. Not per creator: `creator.creators` is
-- already one row per (creator, brand), and two brands paying the same person $400 each have each
-- paid under the threshold. The obligation follows the payer, and in this product each brand is its
-- own payer -- the platform is not a payment aggregator and must not be modelled as one.
--
-- CALENDAR year, not rolling twelve months: the IRS threshold is a calendar-year figure, and a
-- rolling window would withhold payment from someone who is under the actual limit.

begin;

alter table creator.creators
    -- When this platform determined a form is needed -- i.e. when cumulative paid crossed the
    -- threshold, or when a brand asked for one up front. NULL means not yet required.
    add column if not exists tax_form_required_at timestamptz,
    -- When a brand recorded that the form is on file. Deliberately a brand assertion rather than a
    -- Stripe read: on the manual payout rail there is no Stripe account to ask, and a brand paying
    -- by bank transfer still has the obligation. Where Connect IS in use, `payouts_enabled` is the
    -- authoritative signal and this column is not consulted.
    add column if not exists tax_form_on_file_at timestamptz,
    -- Which form. Free text rather than a check constraint: W-9 and W-8BEN cover the two cases this
    -- product will meet first, and a closed set would need a migration the first time someone is in
    -- a country needing a different one.
    add column if not exists tax_form_kind text;

comment on column creator.creators.tax_form_required_at is
    'When THIS platform decided a tax form is needed -- our threshold arithmetic, not a copy of a '
    'Stripe fact. Evidence of why a payout was held.';

comment on column creator.creators.tax_form_on_file_at is
    'When a brand recorded the form as received. Used on the MANUAL rail, where there is no Stripe '
    'account to ask; where Connect is in use, payouts_enabled is authoritative.';

-- The query the threshold check runs: what has this brand paid this creator this calendar year.
-- Only `paid` rows count -- a draft or a failed payout is not money anyone received, and counting
-- them would withhold payment over a threshold that was never actually crossed.
create index if not exists idx_payouts_creator_year
    on finance.influencer_payouts (creator_id, brand_id, paid_at)
    where status = 'paid';

commit;
