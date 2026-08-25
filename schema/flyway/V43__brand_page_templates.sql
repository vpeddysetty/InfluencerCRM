-- =============================================================
-- PR-39 piece D: brand-saved page templates.
-- Date: 2026-08-24
-- Plan: docs/Curated-Section-Editor-Implementation-Plan.md §3
--
-- Purpose:
--   Let a brand save a page it has built and reuse it on the next campaign.
--
-- Why its OWN table, and not a flag on content.landing_templates:
--   `uq_landing_templates_campaign` enforces one page per campaign, and V24 records that as a
--   deliberate product decision — the public slug and the coupon-assignment logic both assume it.
--   A reusable template has no campaign, so storing one in that table would need the constraint
--   relaxed or a nullable campaign_id carved out of it. That would weaken the guarantee for every
--   REAL page in order to store something that is not a page. A separate table leaves the
--   invariant untouched, which is worth more than the reuse of a few columns.
--
-- Sections only, never a rendered document:
--   A saved template stores the section list and its content, and no HTML. That is what lets a
--   later design-system change reach every saved template at once, instead of freezing this
--   month's styling into every page a brand ever saved. It is the same reason the renderer takes
--   sections rather than markup.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

create table if not exists content.brand_page_templates (
    id                 uuid primary key,
    brand_id           uuid not null,
    name               text not null,
    -- The stripped section list: creator identity cleared, coupon tokens kept. See
    -- pageTemplates.stripForTemplate — the stripping is done before it reaches here, because
    -- which fields belong to a campaign rather than to the brand is a product decision, not a
    -- storage one.
    sections           jsonb not null,
    created_by_user_id uuid,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz
);

-- Case-insensitive per brand: "Spring launch" and "spring launch" are the same name to a human,
-- and letting both exist produces a picker with two entries nobody can tell apart.
create unique index if not exists uq_brand_page_templates_name
    on content.brand_page_templates (brand_id, lower(name));

-- The list is always read per brand, and only ever per brand.
create index if not exists ix_brand_page_templates_brand
    on content.brand_page_templates (brand_id);

comment on table content.brand_page_templates is
    'Brand-saved reusable page templates (PR-39). Deliberately separate from '
    'content.landing_templates so uq_landing_templates_campaign keeps meaning one page per '
    'campaign. Stores sections, never rendered HTML.';

commit;
