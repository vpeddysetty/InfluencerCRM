-- Applies idempotent migration scripts during docker init.
-- This file is executed by the official postgres image when mounted in /docker-entrypoint-initdb.d.

\i /docker-entrypoint-initdb.d/migrations/2026_07_17_custom_attributes_and_review_sync.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_19_influencer_code_attribution_tracking.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_source_file.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_import_batch_hydration_status.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_21_mapping_examples_alignment.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_26_workflow_boards.sql
\i /docker-entrypoint-initdb.d/migrations/2026_07_26_workflow_cards.sql
