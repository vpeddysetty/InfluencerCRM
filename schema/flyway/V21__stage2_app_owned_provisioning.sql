-- =============================================================
-- Stage 2: provisioning moves from the database into the application
-- Date: 2026-08-02
-- Roadmap: docs/identity-signup-alignment.md
--
-- The trigger gave every new user an account + brand + OWNER membership. It was a Phase 1
-- bridge, and its own description said so. It has three problems the application does not:
--
--   * it can only create account_type='brand', which is why an agency signup had to create
--     a brand account and then promote it;
--   * it cannot be unit-tested, so the rule that shapes every tenant was unverifiable;
--   * it is invisible to anyone reading the Java, so signup appeared to do less than it did.
--
-- AuthService now calls POST /tenancy/provision explicitly. That endpoint is transactional
-- across all three tables and idempotent on legacy_user_id, so a signup that retries cannot
-- produce two workspaces.
--
-- The FUNCTION is deliberately retained. Rolling back is then a single CREATE TRIGGER
-- (see the block at the foot of this file) rather than restoring code from history.
--
-- Idempotent; safe to re-run.
-- =============================================================

begin;

-- The application now owns this. Dropping only the trigger leaves the function in place.
drop trigger if exists trg_provision_tenancy_for_user on identity.users;

comment on function public.provision_tenancy_for_user() is
    'RETIRED 2026-08-02 (roadmap Stage 2): provisioning moved into AuthService via '
    'POST /tenancy/provision. Kept only so the trigger can be recreated as a rollback. '
    'Do not re-attach without also disabling application-side provisioning, or a signup '
    'will race the trigger for the same legacy_user_id unique index.';

commit;

-- ---------------------------------------------------------------
-- Verification: no user should be left without a workspace.
-- Expect zero rows. A non-empty result means a signup created a user but did not provision,
-- which is the failure mode this migration must not introduce.
-- ---------------------------------------------------------------
-- select u.id, u.email
--   from identity.users u
--   left join identity.memberships m on m.user_id = u.id
--  where m.id is null;

-- ---------------------------------------------------------------
-- Rollback (restores the Phase 1 behaviour):
--
--   create trigger trg_provision_tenancy_for_user
--   after insert on identity.users
--   for each row execute function provision_tenancy_for_user();
--
-- Application-side provisioning is idempotent on legacy_user_id, so with both active the
-- trigger wins the race and the application call returns the existing workspace. That is
-- survivable, but it is not a supported steady state — pick one.
-- ---------------------------------------------------------------
