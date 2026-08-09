-- =============================================================
-- Migration: Per-context database roles  (DDD Phase 5 prerequisite)
-- Date: 2026-08-02
-- Purpose:
--   Create one Postgres role per bounded context, each able to reach only its own
--   schema. This is what turns the Phase 5 schema split from an organisational
--   convention into an enforced boundary.
--
--   Until now every context connects as one role with access to all 24 tables.
--   ArchUnit stops a developer crossing a boundary at compile time; nothing stops
--   the *runtime* doing it. These roles close that gap: when a context becomes its
--   own service, it gets credentials that make a cross-context query fail at the
--   database, not merely at code review.
--
-- Not yet switched on:
--   The application still connects as influencercrm_user. Flipping each service to
--   its own role is a per-service step taken at extraction time — doing it now
--   would break the monolith, which legitimately spans contexts in one connection.
--   The roles exist so that step is a config change rather than a project.
--
-- Read access across contexts:
--   Each role gets SELECT on identity.brands and identity.accounts. Every context
--   validates a brand id, and forcing that through a network call for a foreign-key
--   check would trade a boundary violation for a latency and availability problem.
--   Writes stay strictly owned.
--
-- Passwords:
--   Placeholders only. Set real ones per environment via ALTER ROLE ... PASSWORD,
--   or use IAM/cert auth. Never commit real credentials.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

do $$
declare
    ctx record;
    role_name text;
begin
    for ctx in
        select * from (values
            ('identity'),
            ('creator'),
            ('campaign'),
            ('workflow'),
            ('attribution'),
            ('finance'),
            ('content'),
            ('mapping')
        ) as t(schema_name)
    loop
        role_name := 'svc_' || ctx.schema_name;

        -- 1) The role itself.
        if not exists (select 1 from pg_roles where rolname = role_name) then
            execute format('create role %I with login password %L', role_name, 'change-me-' || ctx.schema_name);
            raise notice 'created role %', role_name;
        end if;

        -- 2) Full ownership of its own schema.
        execute format('grant usage on schema %I to %I', ctx.schema_name, role_name);
        execute format('grant select, insert, update, delete on all tables in schema %I to %I',
                       ctx.schema_name, role_name);
        execute format('grant usage, select on all sequences in schema %I to %I',
                       ctx.schema_name, role_name);
        -- Tables added later must be reachable too, or the next migration silently
        -- breaks the service.
        execute format('alter default privileges in schema %I grant select, insert, update, delete on tables to %I',
                       ctx.schema_name, role_name);

        -- 3) Everyone may write the outbox: publishing an event is how contexts talk.
        execute format('grant usage on schema shared to %I', role_name);
        execute format('grant select, insert, update on shared.domain_events to %I', role_name);

        -- 4) Read-only view of the tenancy spine, for brand validation.
        if ctx.schema_name <> 'identity' then
            execute format('grant usage on schema identity to %I', role_name);
            execute format('grant select on identity.brands, identity.accounts to %I', role_name);
        end if;

        -- 5) Shared enum types live in public; without usage the role cannot cast to them.
        execute format('grant usage on schema public to %I', role_name);

        -- 6) Resolve unqualified names the same way the app does.
        execute format('alter role %I set search_path = %I, shared, identity, public',
                       role_name, ctx.schema_name);
    end loop;
end $$;

-- =============================================================
-- Post-conditions
-- =============================================================
do $$
declare
    missing text;
    leaked  text;
begin
    -- Every role must exist.
    select string_agg(expected, ', ') into missing
      from (values ('svc_identity'),('svc_creator'),('svc_campaign'),('svc_workflow'),
                   ('svc_attribution'),('svc_finance'),('svc_content'),('svc_mapping')) as t(expected)
     where not exists (select 1 from pg_roles where rolname = expected);
    if missing is not null then
        raise exception 'Phase 5: missing context roles: %', missing;
    end if;

    -- The whole point: finance must NOT be able to write another context's tables.
    -- has_table_privilege answers this directly rather than by inspecting grants.
    select string_agg(t.tbl, ', ') into leaked
      from (values ('creator.creators'), ('campaign.campaigns'), ('workflow.workflow_boards')) as t(tbl)
     where has_table_privilege('svc_finance', t.tbl, 'INSERT');
    if leaked is not null then
        raise exception 'Phase 5: svc_finance can write tables it does not own: %', leaked;
    end if;

    if not has_table_privilege('svc_finance', 'finance.influencer_payouts', 'INSERT') then
        raise exception 'Phase 5: svc_finance cannot write its own tables';
    end if;

    if not has_table_privilege('svc_finance', 'shared.domain_events', 'INSERT') then
        raise exception 'Phase 5: svc_finance cannot publish domain events';
    end if;

    if not has_table_privilege('svc_finance', 'identity.brands', 'SELECT') then
        raise exception 'Phase 5: svc_finance cannot read the tenancy spine';
    end if;

    raise notice 'Phase 5 context roles OK: 8 roles, each scoped to its own schema, '
                 'all able to publish events and read brands.';
end $$;
