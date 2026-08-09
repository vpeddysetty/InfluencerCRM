-- =====================================================================
-- M3 item 0b — an idempotency key for order attribution
-- Date: 2026-08-09
-- =====================================================================
--
-- WHY
--
-- `AttributionService.findExistingAttribution` already runs before every write, so this is not a
-- missing check. It is a read followed by a write with nothing underneath it: two concurrent
-- deliveries of the same order can both read "not found" and both insert.
--
-- That race is theoretical against the mock provider and routine against Shopify, which retries
-- `orders/paid` until it gets a 2xx and will happily deliver the same order twice within
-- milliseconds. The consequence is double-counted revenue and double-accrued commission, which
-- becomes a real payout to a creator for one sale counted twice.
--
-- The billing side already solved exactly this with `idx_billing_events_provider_event`. Same
-- lesson as M8.3 payout references: the money path needs an idempotency key, and the key must come
-- from the provider.
--
-- WHY TWO INDEXES RATHER THAN ONE
--
-- `order_line_id` is nullable, and in Postgres `null` is never equal to `null`. A single
-- `unique (brand_id, order_id, order_line_id)` therefore does NOT collide when the line id is
-- null — every order-level delivery would look distinct to the constraint. That is precisely the
-- `orders/paid` case, the one that retries most, so the obvious single index would silently fail
-- at the only job it was added to do.
--
-- Two partial indexes state both cases explicitly and are separately greppable. `coalesce(
-- order_line_id, '')` would also work in one index, but it hides the null case inside an
-- expression and reads as an accident rather than a decision.
--
-- WHY campaign_code_id IS NOT IN THE KEY
--
-- The existing in-code check includes it, but it must NOT be part of the constraint. The rule
-- being enforced is "this order line is attributed at most once" — not "at most once per coupon".
-- Including the code would let a second delivery of the same real order, naming a different coupon
-- code, insert a second attribution and accrue commission twice for one sale. The narrower key is
-- the one that actually protects the money.
--
-- SAFETY
--
-- Additive and reversible; no column, row or existing index is modified. The migration ABORTS if
-- duplicates already exist rather than failing halfway through index creation, so a dirty table is
-- reported as data to reconcile rather than as an opaque constraint error.

begin;

-- -------------------------------------------------------------
-- 1. Refuse to run against data that already violates the rule.
-- -------------------------------------------------------------
-- Creating a unique index over duplicate rows fails anyway; doing the check first means the
-- operator gets the offending keys and a count instead of a Postgres error naming one row.
do $$
declare
    dupes bigint;
    sample text;
begin
    select count(*), coalesce(string_agg(detail, '; '), '')
      into dupes, sample
      from (
          select 'brand=' || brand_id
                 || ' order=' || order_id
                 || ' line=' || coalesce(order_line_id, '<null>')
                 || ' x' || count(*) as detail
            from influencer_sale_attributions
           group by brand_id, order_id, order_line_id
          having count(*) > 1
           limit 10
      ) d;

    if dupes > 0 then
        raise exception
            'ABORT: % duplicate (brand_id, order_id, order_line_id) group(s) already exist. '
            'These are double-counted sales and must be reconciled before the constraint can be '
            'added, because collapsing them silently would erase revenue history. Samples: %',
            dupes, sample;
    end if;
end $$;

-- -------------------------------------------------------------
-- 2. The idempotency key.
-- -------------------------------------------------------------
-- Line-level events: the line id distinguishes rows within one order.
create unique index if not exists uq_isa_brand_order_line
    on influencer_sale_attributions (brand_id, order_id, order_line_id)
    where order_line_id is not null;

-- Order-level events: no line id, so the order alone is the key. Without this partial index the
-- null case is unconstrained -- see the note above; this is the half that catches `orders/paid`.
create unique index if not exists uq_isa_brand_order_no_line
    on influencer_sale_attributions (brand_id, order_id)
    where order_line_id is null;

comment on index uq_isa_brand_order_line is
    'Idempotency key for line-level order events. Paired with uq_isa_brand_order_no_line, which '
    'covers the null-line case that this index cannot see because null <> null in Postgres.';

comment on index uq_isa_brand_order_no_line is
    'Idempotency key for order-level events (no line id). Deliberately excludes campaign_code_id: '
    'an order line is attributed at most once regardless of which coupon code claims it.';

commit;

-- -------------------------------------------------------------
-- Verify
-- -------------------------------------------------------------
--   select indexname from pg_indexes
--    where tablename = 'influencer_sale_attributions'
--      and indexname in ('uq_isa_brand_order_line', 'uq_isa_brand_order_no_line');
--   -- expect 2 rows

-- -------------------------------------------------------------
-- Rollback
-- -------------------------------------------------------------
-- Additive only. Dropping both returns ingestion to check-then-act, which is the pre-M3 behaviour.
--
--   begin;
--   drop index if exists uq_isa_brand_order_line;
--   drop index if exists uq_isa_brand_order_no_line;
--   commit;
