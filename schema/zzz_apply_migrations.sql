-- Applies idempotent migration scripts during docker init.
-- This file is executed by the official postgres image when mounted in /docker-entrypoint-initdb.d.

\i /docker-entrypoint-initdb.d/migrations/2026_07_17_custom_attributes_and_review_sync.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_19_creator_brand_owner_workflow.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_19_influencer_code_attribution_tracking.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_source_file.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_hydration_status.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_workflow_swimlanes.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_remove_workflow_swimlanes.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_campaign_type_and_workflow_setup.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_campaign_type_presets_seed.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_workflow_setup_tie_existing_campaigns_work_items.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_mapping_examples_alignment.sql
