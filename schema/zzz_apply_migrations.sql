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
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_creator_collaboration.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_accounts_brands_memberships.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase2_brand_tenancy_cutover.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase2_legacy_user_fk.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase4_outbox.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_schema_per_context.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_context_db_roles.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_workflow_extraction.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_refresh_tokens.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase5_sever_all_cross_context_fks.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_phase6_federated_identities.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_stage2_app_owned_provisioning.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_stage3_member_invitations.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_stage4_creator_identity.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_02_landing_builder_reset.sql
-- Everything from here down was written but never added to this list, so a database created from
-- this file — which is now how EVERY containerized environment is created — came up without the
-- landing builder, assets, creator onboarding, vetting, domains, collaborators, billing, expiry
-- warnings, or the order-idempotency index. The services would start and then fail on first query
-- against a table that does not exist.
--
-- Kept in filename date order: these are not independent. phase_a creates the landing tables that
-- phase_b's assets and m5_6's expiry warnings then alter.
\i /docker-entrypoint-initdb.d/migrations/2026_08_05_phase_a_landing_builder.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_05_phase_b_assets.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_05_phase_c_creator_onboarding.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_05_phase_d_stage_automation.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_06_phase_c2_vetting.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_06_phase_c3_creator_health.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_06_phase_e_domains.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_06_phase_g_collaborators.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_07_m2_subscriptions_billing.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_07_m5_6_hosting_expiry_warnings.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_07_m6_creator_platform_tokens.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_09_m3_order_attribution_idempotency.sql
\i /docker-entrypoint-initdb.d/migrations/2026_08_11_consent_records.sql
