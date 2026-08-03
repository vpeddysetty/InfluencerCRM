-- =============================================================
-- Landing builder reset: clear pre-builder template fixtures
-- Date: 2026-08-02
-- Roadmap: docs/landing-page-builder-roadmap.md §6.2, §10.1
--
-- Purpose:
--   The landing page builder (Phase A) adopts GrapesJS, whose document model becomes the
--   shape of landing_templates.blocks. The rows currently in the table were written by the
--   hand-built page editor and use a different block structure.
--
--   Migrating them would mean a mapper, a dual renderer and a compatibility mode for pages
--   nobody uses. Clearing them means GrapesJS defines the schema outright.
--
-- Why deletion rather than status='legacy':
--   uq_landing_templates_campaign is UNIQUE on campaign_id. Leaving stale rows would block
--   creating a new page for those campaigns — the fixtures would be an active obstacle, not
--   merely untidy.
--
-- Verified before writing this (2026-08-02):
--   * 12 template rows, every one owned by a test account (e2e_*@example.com,
--     ui_*@example.com, demo.brand@luminaskin.example)
--   * 17 landing_page_views, all recorded on 2026-08-01 — a single E2E run, not traffic
--   * names are fixtures: "Summer Landing", "Landing page", "Test"
--
--   The guard below re-checks that at run time rather than trusting this comment. If a real
--   brand has published a page since, the migration aborts instead of deleting it.
--
-- Scope:
--   Data only. No table, column, index or constraint is altered — LandingService.renderPublic,
--   the analytics columns and the public /s/{slug} route are all untouched.
--
-- Idempotent: re-running on an already-clear table is a no-op.
-- =============================================================

begin;

-- -------------------------------------------------------------
-- Guard: refuse to run if any template looks like real customer data.
-- -------------------------------------------------------------
-- A page is "real" if its owning account traces to a user outside the known test-account
-- patterns. Aborting is the right failure here: deleting a live page a partner has linked to
-- is unrecoverable, while an aborted migration costs a conversation.
do $$
declare
    real_pages integer;
begin
    select count(*)
      into real_pages
      from content.landing_templates lt
      join identity.brands b   on b.id = lt.brand_id
      join identity.accounts a on a.id = b.account_id
      left join identity.users u on u.id = a.legacy_user_id
     where u.email is null
        or (u.email not like 'e2e/_%' escape '/'
        and u.email not like 'ui/_%' escape '/'
        and u.email not like 'demo.%'
        and u.email not like 'solo.%'
        and u.email not like '%@example.com'
        and u.email not like '%@example.test');

    if real_pages > 0 then
        raise exception
            'Aborting: % landing template(s) belong to non-test accounts. '
            'Review them before clearing — see docs/landing-page-builder-roadmap.md §10.1.',
            real_pages;
    end if;
end $$;

-- -------------------------------------------------------------
-- Clear the fixtures.
-- -------------------------------------------------------------
-- Views first: they reference campaign codes and brands, and keeping analytics for pages that
-- no longer exist would leave the funnel reporting a denominator it cannot explain.
delete from content.landing_page_views
 where brand_id in (select brand_id from content.landing_templates);

delete from content.landing_templates;

commit;

-- ---------------------------------------------------------------
-- Verification — expect 0, 0.
--
--   select (select count(*) from content.landing_templates)  as templates,
--          (select count(*) from content.landing_page_views) as views;
--
-- Rollback:
--   None. This deletes test fixtures with no production value; the recovery path is a
--   database restore, and the guard above is what makes that unnecessary. Everything the
--   builder needs is recreated by using it.
-- ---------------------------------------------------------------
