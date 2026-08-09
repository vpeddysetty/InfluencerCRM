-- =============================================================
-- Migration: Accounts / Brands / Memberships  (DDD Phase 1)
-- Date: 2026-08-02
-- Purpose:
--   Introduce the multi-tenant spine that lets one account be either a single
--   brand OR an agency managing many brands, and re-key every domain table from
--   user_id to brand_id.
--
--   See docs/ddd-roadmap.md (Phase 1) and docs/architecture-migration-plan.md
--   (sections 3.2 - 3.4) for the full rationale.
--
-- Shape:
--   accounts     -- the paying entity: 'brand' (solo) or 'agency'
--     +-- brands        -- a managed brand; solo accounts have exactly one
--     +-- memberships   -- user <-> account, carries the account-level role
--           +-- brand_access  -- membership <-> brand, per-brand role
--
--   A solo brand user is simply an account of type 'brand' with one brand and
--   one OWNER membership. That is deliberate: the single-brand product is the
--   degenerate case of the agency product, so there is one code path, not two.
--
-- CRITICAL - this migration is deliberately NON-DESTRUCTIVE:
--   * user_id columns are KEPT and remain the live tenancy key. Nothing in the
--     running application changes; brand_id is populated but not yet read.
--   * Phase 2 switches the runtime over to brand_id.
--   * Phase 2 (only once stable in production) drops user_id.
--   user_id IS THE ROLLBACK PATH. Do not drop it here.
--
-- Notes:
--   - Idempotent by design (safe to re-run).
--   - Additive only: no data is deleted or overwritten.
--   - Run schema/verify/2026_08_02_phase1_reconciliation.sql afterwards; it must
--     report zero drift before this migration is considered successful.
-- =============================================================

create extension if not exists "pgcrypto";

-- =============================================================
-- 1) Account-level role enum
-- =============================================================
-- OWNER   - account: billing, delete account, manage admins (exactly one required)
-- ADMIN   - account: manage brands, invite/remove members, all brand data
-- MANAGER - brand:   full control of assigned brands incl. approving commissions
-- MARKETER- brand:   day-to-day campaigns/creators/outreach. No financial approval.
-- ANALYST - brand:   read-only + exports. The safe default for contractors.
-- FINANCE - account: commissions and payouts across all brands. No campaign edits.
--
-- Separation of duties (see plan section 4.2): MANAGER may approve commissions but
-- NOT create or approve payouts; FINANCE may do both but cannot edit campaign data.
-- This is the control agencies get audited on - do not collapse these roles.
do $$
begin
    if not exists (select 1 from pg_type where typname = 'account_role') then
        create type account_role as enum ('OWNER','ADMIN','MANAGER','MARKETER','ANALYST','FINANCE');
    end if;
end $$;

-- =============================================================
-- 2) Core tenancy tables
-- =============================================================

create table if not exists accounts (
    id           uuid primary key default gen_random_uuid(),
    name         text not null,
    account_type text not null default 'brand' check (account_type in ('brand','agency')),
    plan         text not null default 'free',
    status       text not null default 'active',
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

comment on table accounts is
    'The paying entity. account_type=brand is a solo brand; account_type=agency manages many brands.';

create table if not exists brands (
    id                uuid primary key default gen_random_uuid(),
    account_id        uuid not null references accounts(id) on delete cascade,
    name              text not null,
    status            text not null default 'active',
    custom_attributes jsonb not null default '{}'::jsonb,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    -- Two brands in one account may not share a name; across accounts they may.
    unique (account_id, name)
);

comment on table brands is
    'A managed brand. THE tenancy key for every domain table from Phase 2 onward.';

create table if not exists memberships (
    id         uuid primary key default gen_random_uuid(),
    account_id uuid not null references accounts(id) on delete cascade,
    user_id    uuid not null references users(id)    on delete cascade,
    role       account_role not null,
    status     text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    -- A user holds at most one membership per account.
    unique (account_id, user_id)
);

comment on table memberships is
    'user <-> account with the account-level role. Replaces the vestigial users.role column.';

-- Scopes a member to specific brands. A membership with NO rows here whose role is
-- OWNER or ADMIN implicitly has access to ALL brands in the account; that keeps the
-- common "agency owner sees everything" case from requiring N rows per new brand.
create table if not exists brand_access (
    id            uuid primary key default gen_random_uuid(),
    membership_id uuid not null references memberships(id) on delete cascade,
    brand_id      uuid not null references brands(id)      on delete cascade,
    role          account_role not null,
    created_at    timestamptz not null default now(),
    unique (membership_id, brand_id)
);

comment on table brand_access is
    'Per-brand scoping for a membership. Absent rows + OWNER/ADMIN = access to all brands.';

create index if not exists idx_brands_account       on brands(account_id);
create index if not exists idx_memberships_account  on memberships(account_id);
create index if not exists idx_memberships_user     on memberships(user_id);
create index if not exists idx_brand_access_member  on brand_access(membership_id);
create index if not exists idx_brand_access_brand   on brand_access(brand_id);

-- =============================================================
-- 3) Backfill: every existing user becomes account + brand + OWNER membership
-- =============================================================
-- Correlation is carried on the rows themselves (legacy_user_id) rather than in a
-- temp table, so this migration stays idempotent and re-runnable, and so Phase 2
-- can still map user_id -> brand_id if it needs to.

alter table accounts add column if not exists legacy_user_id uuid;
alter table brands   add column if not exists legacy_user_id uuid;

comment on column accounts.legacy_user_id is
    'Phase 1 migration correlation: the users.id this account was derived from. Dropped in Phase 2.';
comment on column brands.legacy_user_id is
    'Phase 1 migration correlation: the users.id this brand was derived from. Dropped in Phase 2.';

create unique index if not exists uq_accounts_legacy_user on accounts(legacy_user_id) where legacy_user_id is not null;
create unique index if not exists uq_brands_legacy_user   on brands(legacy_user_id)   where legacy_user_id is not null;

-- 3a) one account per existing user
insert into accounts (name, account_type, plan, legacy_user_id, created_at, updated_at)
select
    coalesce(nullif(btrim(u.brand_name), ''), split_part(u.email, '@', 1)),
    'brand',                    -- existing users are all solo brands; agencies are created going forward
    coalesce(nullif(btrim(u.plan), ''), 'free'),
    u.id,
    u.created_at,
    u.updated_at
from users u
where not exists (select 1 from accounts a where a.legacy_user_id = u.id);

-- 3b) one brand per account
insert into brands (account_id, name, custom_attributes, legacy_user_id, created_at, updated_at)
select
    a.id,
    coalesce(nullif(btrim(u.brand_name), ''), split_part(u.email, '@', 1)),
    coalesce(u.custom_attributes, '{}'::jsonb),
    u.id,
    u.created_at,
    u.updated_at
from users u
join accounts a on a.legacy_user_id = u.id
where not exists (select 1 from brands b where b.legacy_user_id = u.id);

-- 3c) one OWNER membership per user
-- Every pre-existing user owned their own workspace outright, so OWNER is the only
-- role that preserves their current capabilities. users.role was never enforced
-- (it defaulted to 'owner' and was hardcoded at signup), so there is nothing to map.
insert into memberships (account_id, user_id, role, created_at, updated_at)
select a.id, u.id, 'OWNER'::account_role, u.created_at, u.updated_at
from users u
join accounts a on a.legacy_user_id = u.id
where not exists (
    select 1 from memberships m where m.account_id = a.id and m.user_id = u.id
);

-- No brand_access rows are created: an OWNER with no explicit scoping implicitly
-- reaches every brand in the account, which is exactly the pre-migration behaviour.

-- =============================================================
-- 4) Add brand_id to every domain table, backfill, and constrain
-- =============================================================
-- All 18 tables are handled uniformly. user_id is preserved throughout.
--
-- mapping_examples is the one table whose user_id is NULLABLE (it uses
-- "on delete set null" so shared/global mapping examples survive user deletion).
-- Its brand_id therefore stays nullable too - forcing not-null would either
-- fabricate ownership for global examples or fail the migration outright.
do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views','mapping_examples'
    ];
    orphan_count bigint;
begin
    foreach t in array tenant_tables loop
        -- 4a) add the column (nullable for now so the backfill can run)
        execute format('alter table %I add column if not exists brand_id uuid', t);

        -- 4b) backfill from the owning user's brand
        execute format(
            'update %I tgt set brand_id = b.id
               from brands b
              where b.legacy_user_id = tgt.user_id
                and tgt.brand_id is distinct from b.id
                and tgt.user_id is not null', t);

        -- 4c) FK to brands. Cascade mirrors the existing user_id cascade, so
        --     removing a brand cleans up its rows exactly as removing a user did.
        if not exists (
            select 1 from information_schema.table_constraints
            where table_name = t and constraint_name = 'fk_' || t || '_brand'
        ) then
            execute format(
                'alter table %I add constraint %I foreign key (brand_id)
                     references brands(id) on delete cascade',
                t, 'fk_' || t || '_brand');
        end if;

        -- 4d) enforce not-null wherever user_id is not-null. Guarded: if any row
        --     failed to map we raise instead of silently leaving a nullable column,
        --     because a silent partial backfill is what produces cross-tenant leaks
        --     in Phase 2.
        if t <> 'mapping_examples' then
            execute format('select count(*) from %I where brand_id is null', t) into orphan_count;
            if orphan_count > 0 then
                raise exception
                    'Phase 1 backfill incomplete: %.brand_id is null for % row(s). '
                    'Every row must map to a brand before NOT NULL can be applied.',
                    t, orphan_count;
            end if;
            execute format('alter table %I alter column brand_id set not null', t);
        end if;

        -- 4e) mirror the tenant index that every list view relies on
        execute format('create index if not exists %I on %I(brand_id)', 'idx_' || t || '_brand', t);
    end loop;
end $$;

-- =============================================================
-- 3bis) Auto-provision the tenancy spine for NEW users
-- =============================================================
-- The section 3 backfill is a one-time snapshot of users that existed when it ran.
-- Signup, however, still only inserts a users row - it knows nothing about accounts
-- or brands until Phase 2. Without this trigger every user created after the
-- migration would have no brand, and their first write would fail.
--
-- Provisioning therefore happens in the database for the Phase 1 -> Phase 2 window.
-- Phase 2 moves it into the Identity context's signup use case and drops this
-- trigger, at which point account type and brand naming become real product choices
-- rather than a derived default.
create or replace function provision_tenancy_for_user() returns trigger as $$
declare
    new_account_id uuid;
    new_brand_id   uuid;
    resolved_name  text;
begin
    -- Idempotent: a user provisioned by the backfill (or a re-run) is left alone.
    if exists (select 1 from accounts where legacy_user_id = new.id) then
        return new;
    end if;

    resolved_name := coalesce(nullif(btrim(new.brand_name), ''), split_part(new.email, '@', 1));

    insert into accounts (name, account_type, plan, legacy_user_id, created_at, updated_at)
    values (resolved_name, 'brand', coalesce(nullif(btrim(new.plan), ''), 'free'),
            new.id, new.created_at, new.updated_at)
    returning id into new_account_id;

    insert into brands (account_id, name, custom_attributes, legacy_user_id, created_at, updated_at)
    values (new_account_id, resolved_name, coalesce(new.custom_attributes, '{}'::jsonb),
            new.id, new.created_at, new.updated_at)
    returning id into new_brand_id;

    -- A self-signup owns their workspace outright, exactly as before this migration.
    insert into memberships (account_id, user_id, role, created_at, updated_at)
    values (new_account_id, new.id, 'OWNER'::account_role, new.created_at, new.updated_at)
    on conflict (account_id, user_id) do nothing;

    return new;
end;
$$ language plpgsql;

comment on function provision_tenancy_for_user() is
    'Phase 1 bridge: gives every new user an account + brand + OWNER membership, '
    'because signup does not yet do so. Moves into the app in Phase 2.';

drop trigger if exists trg_provision_tenancy_for_user on users;
create trigger trg_provision_tenancy_for_user
    after insert on users
    for each row execute function provision_tenancy_for_user();

-- =============================================================
-- 4bis) Bridge trigger: derive brand_id on write while the app still sends user_id
-- =============================================================
-- Without this, section 4d's NOT NULL breaks every INSERT: the Phase 1 application
-- knows nothing about brand_id, so it writes user_id only and Postgres rejects the
-- row. That would violate Phase 1's core contract ("populate brand_id, change no
-- behaviour") and take down all creates.
--
-- The trigger fills brand_id from the writer's user_id, making the column populate
-- itself for the whole Phase 1 -> Phase 2 window. It also backstops the reverse
-- direction so that code already migrated to brand_id keeps user_id in step, which
-- is what lets Phase 2 roll out table by table instead of in one atomic switch.
--
-- Phase 2 drops this trigger in the same release that drops user_id.
create or replace function sync_brand_tenancy() returns trigger as $$
declare
    resolved_brand_id uuid;
    resolved_user_id  uuid;
begin
    -- Forward: app wrote user_id (Phase 1 behaviour) -> derive brand_id.
    if new.brand_id is null and new.user_id is not null then
        select b.id into resolved_brand_id from brands b where b.legacy_user_id = new.user_id;
        if resolved_brand_id is null then
            raise exception
                'No brand found for user_id % (table %). Every user must have a brand - '
                'run the Phase 1 backfill before writing.', new.user_id, tg_table_name;
        end if;
        new.brand_id := resolved_brand_id;
    end if;

    -- Reverse: code already on brand_id (early Phase 2) -> keep user_id populated so
    -- the rollback path and any not-yet-migrated reader stay correct.
    if new.user_id is null and new.brand_id is not null then
        select b.legacy_user_id into resolved_user_id from brands b where b.id = new.brand_id;
        new.user_id := resolved_user_id;
    end if;

    -- Audit: attribute the row to its writer when the app has not said otherwise.
    if new.created_by_user_id is null then
        new.created_by_user_id := new.user_id;
    end if;

    return new;
end;
$$ language plpgsql;

comment on function sync_brand_tenancy() is
    'Phase 1 bridge: keeps user_id and brand_id in step so the app can migrate one '
    'tier at a time. Dropped in Phase 2 together with user_id.';

do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views','mapping_examples'
    ];
begin
    foreach t in array tenant_tables loop
        execute format('drop trigger if exists trg_sync_brand_tenancy on %I', t);
        execute format(
            'create trigger trg_sync_brand_tenancy
                 before insert or update on %I
                 for each row execute function sync_brand_tenancy()', t);
    end loop;
end $$;

-- =============================================================
-- 5) Mirror the remaining composite (user_id, ...) indexes
-- =============================================================
-- Section 4e covers the plain single-column tenant index. These are the compound
-- ones that back specific hot queries; each must exist on brand_id before Phase 2
-- flips the runtime over, or those queries fall back to sequential scans.
create index if not exists idx_das_brand_day        on daily_attribution_stats(brand_id, day);
create index if not exists idx_ic_brand_status      on influencer_commissions(brand_id, status);
create index if not exists idx_ip_brand_status      on influencer_payouts(brand_id, status);
create index if not exists idx_mc_brand_provider    on marketplace_connections(brand_id, provider_key);
create index if not exists idx_workflow_boards_brand_position
    on workflow_boards(brand_id, "position");

-- =============================================================
-- 6) Rewrite the unique constraints that break under multi-brand
-- =============================================================
-- These three are keyed on user_id and are WRONG once an agency manages several
-- brands. Each existing user maps to exactly one brand, so every rewrite below is
-- a 1:1 re-key with zero collisions and no merge decisions.
--
-- The old user_id constraints are intentionally LEFT IN PLACE: they remain correct
-- while user_id is still the live tenancy key, and dropping them here would remove
-- protection during the Phase 1 -> Phase 2 window. Phase 2 drops them together with
-- the user_id columns.
--
-- CONSEQUENCE, verified in rehearsal: while creators_user_id_platform_handle_key and
-- uq_influencer_campaign_codes_user_code still exist, an agency CANNOT yet register
-- the same creator handle (or coupon code) under two of its brands - the legacy
-- constraint rejects it, because both rows share one owning user_id.
--
-- That is correct for Phase 1, whose whole contract is "change no behaviour". It does
-- mean the agency capability is not actually usable until Phase 2 drops these two
-- constraints. Phase 2 must therefore drop them in the SAME release that switches the
-- runtime to brand_id - not later - or multi-brand will appear broken to the first
-- agency that tries it.

-- 6a) creators: per-brand creator identity.
-- Decision (plan section 3.4): the same handle under two brands is TWO rows, not one.
-- Most creator fields (preferred_rate, minimum_fee, brand_safety_score, safety_notes)
-- are relationship data, not identity data - a shared row would force one brand's
-- negotiated rate and safety assessment onto its competitors inside the same agency.
create unique index if not exists uq_creators_brand_platform_handle
    on creators(brand_id, platform, handle);

-- 6b) influencer_campaign_codes: codes are brand-facing artifacts pushed to a
-- brand's own storefront, so two brands may each issue "SUMMER20".
create unique index if not exists uq_icc_brand_code
    on influencer_campaign_codes(brand_id, code);

-- 6c) daily_attribution_stats: the rollup grain silently collapses across brands
-- if it stays keyed on user_id.
create unique index if not exists uq_das_grain_brand
    on daily_attribution_stats(brand_id, day, creator_id, campaign_id, channel);

-- =============================================================
-- 7) Audit: who created the row
-- =============================================================
-- brand_id answers "which tenant owns this"; it cannot answer "which marketer
-- added it". With several people working inside one brand that question arrives
-- almost immediately, and the information is unrecoverable if not captured now.
do $$
declare
    t text;
    tenant_tables text[] := array[
        'creators','campaigns','campaign_creators','interactions','import_batches',
        'workflow_boards','workflow_board_stages','workflow_cards',
        'influencer_campaign_codes','influencer_sale_attributions',
        'influencer_commissions','influencer_payouts','daily_attribution_stats',
        'marketplace_connections','campaign_briefs','landing_templates',
        'landing_page_views','mapping_examples'
    ];
begin
    foreach t in array tenant_tables loop
        execute format('alter table %I add column if not exists created_by_user_id uuid', t);

        if not exists (
            select 1 from information_schema.table_constraints
            where table_name = t and constraint_name = 'fk_' || t || '_created_by'
        ) then
            -- set null, not cascade: removing a user must not delete brand-owned work.
            execute format(
                'alter table %I add constraint %I foreign key (created_by_user_id)
                     references users(id) on delete set null',
                t, 'fk_' || t || '_created_by');
        end if;

        -- Seed from the existing owner. For pre-migration rows the owning user is
        -- the only creator we can attest to.
        execute format(
            'update %I set created_by_user_id = user_id
              where created_by_user_id is null and user_id is not null', t);
    end loop;
end $$;

-- =============================================================
-- 8) Post-conditions
-- =============================================================
-- Fail loudly here rather than let Phase 2 inherit a half-migrated database.
do $$
declare
    user_count      bigint;
    account_count   bigint;
    brand_count     bigint;
    membership_count bigint;
begin
    select count(*) into user_count       from users;
    select count(*) into account_count    from accounts where legacy_user_id is not null;
    select count(*) into brand_count      from brands   where legacy_user_id is not null;
    select count(*) into membership_count from memberships;

    if account_count <> user_count then
        raise exception 'Phase 1: expected one account per user (users=%, accounts=%)',
            user_count, account_count;
    end if;
    if brand_count <> user_count then
        raise exception 'Phase 1: expected one brand per user (users=%, brands=%)',
            user_count, brand_count;
    end if;
    if membership_count < user_count then
        raise exception 'Phase 1: expected at least one membership per user (users=%, memberships=%)',
            user_count, membership_count;
    end if;

    raise notice 'Phase 1 backfill OK: % users -> % accounts, % brands, % memberships',
        user_count, account_count, brand_count, membership_count;
end $$;
