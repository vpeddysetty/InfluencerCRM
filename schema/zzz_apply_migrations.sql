-- Applies idempotent migration scripts during docker init.
-- This file is executed by the official postgres image when mounted in /docker-entrypoint-initdb.d.

\i /docker-entrypoint-initdb.d/migrations/2026_07_17_custom_attributes_and_review_sync.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_19_influencer_code_attribution_tracking.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_source_file.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_hydration_status.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_mapping_examples_alignment.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_26_workflow_boards.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_26_workflow_cards.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_27_coupons_marketplace_commissions.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_01_content_creation.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_accounts_brands_memberships.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase2_brand_tenancy_cutover.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase2_legacy_user_fk.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase4_outbox.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_schema_per_context.sql
