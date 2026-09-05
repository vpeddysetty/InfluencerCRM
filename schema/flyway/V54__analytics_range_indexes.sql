-- Composite indexes for date-ranged analytics (roadmap OP-39, groundwork for PR-64).
--
-- WHAT WAS ACTUALLY SLOW. `AnalyticsService.influencerRevenue` fetched EVERY sale attribution,
-- creator and workflow card a brand had ever had, then filtered by date in Java. The DAO endpoints
-- accepted no date parameters, so the BFF could not have narrowed the query even if it wanted to.
-- That is O(all rows ever) per dashboard render, and it degrades as a customer uses the product --
-- the one performance curve that punishes the most engaged account. `PR-64`'s portfolio view
-- multiplies it by the client count: an agency with eight brands would trigger 32 unbounded fetches
-- on every render.
--
-- WHY A COMPOSITE AND NOT THE TWO SINGLE-COLUMN INDEXES THAT ALREADY EXIST.
-- `idx_influencer_sale_attributions_brand` (brand_id) and `idx_isa_occurred_at` (occurred_at) are
-- both present, and neither answers `where brand_id = ? and occurred_at >= ? and occurred_at < ?`
-- well. Postgres can bitmap-and them, but that reads both index structures and then rechecks the
-- heap; a single index whose leading column is the equality predicate and whose second is the range
-- gives one ordered scan of exactly the rows wanted. Column order matters and is not arbitrary:
-- equality first, range second. Reversed, every brand's rows in the window are scanned and then
-- discarded.
--
-- The single-column indexes are KEPT, not replaced. `idx_isa_occurred_at` still serves queries with
-- no brand predicate (an operator asking what happened yesterday across the platform), and dropping
-- an index to tidy up is how a query nobody was thinking about becomes a sequential scan.
--
-- NO ROLLUP TABLE IS POPULATED HERE, deliberately. `daily_attribution_stats` exists (V8) and nothing
-- writes to it; §12.2b records why materialising it is the wrong purchase today -- a read model is
-- eventually consistent and this is money data, at zero subscribers, on an in-process outbox relay.
-- Pushing the aggregation into SQL against the transactional tables is strongly consistent and
-- roughly two orders of magnitude less data on the wire, which is where the win actually is. When a
-- real customer's dashboard exceeds ~500ms, revisit -- measured, not predicted.

begin;

-- The exact predicate the ranged analytics queries run.
create index if not exists idx_isa_brand_occurred
    on attribution.influencer_sale_attributions (brand_id, occurred_at);

-- Workflow cards carry the flat fees ROI is computed against, windowed on `created_at` -- which is
-- the closest thing to "when this fee was agreed" the row carries, and the approximation
-- AnalyticsService already documents. Same shape, same reasoning.
create index if not exists idx_workflow_cards_brand_created
    on workflow.workflow_cards (brand_id, created_at);

-- Commissions are read per brand and windowed on when they were earned.
create index if not exists idx_commissions_brand_created
    on finance.influencer_commissions (brand_id, created_at);

commit;
