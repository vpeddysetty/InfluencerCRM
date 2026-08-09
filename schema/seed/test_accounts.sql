-- =============================================================
-- SUPERSEDED 2026-08-02 by tests/seed_demo_accounts.sh (roadmap Stage 3).
--
-- This script exists only as the record of what the platform used to require. Everything below
-- is now doable through the product: member invitations mean a user can be added to someone
-- else's account without deleting the solo account their signup created and re-parenting them.
--
-- Prefer:  bash tests/seed_demo_accounts.sh
-- Keep this file for reference; do not extend it.
-- =============================================================

-- =============================================================
-- Seed: demo accounts for manual validation
-- Date: 2026-08-02
-- Purpose:
--   Stable, known-credential accounts covering every role and both account types,
--   so the behaviour described in docs/TEST-REPORT.md can be re-checked by hand.
--
-- Users are created through the real signup endpoint (so the password hash is
-- genuine); this script only re-parents them onto the demo agency and assigns
-- roles. See docs/TEST-REPORT.md for the credentials and what each account proves.
--
-- Safe to re-run. Not for production: every account shares one well-known password.
-- =============================================================

-- The Phase 1 provisioning trigger gives each new signup its own solo account.
-- Demo agency members must instead belong to the shared agency account, so their
-- auto-created solo accounts are removed first.
delete from identity.accounts
 where legacy_user_id in (
     select id from identity.users
      where email in (
          'demo.admin@northstar.test',
          'demo.manager@northstar.test',
          'demo.marketer@northstar.test',
          'demo.analyst@northstar.test',
          'demo.finance@northstar.test'
      ));

-- The agency and its two client brands.
insert into identity.accounts (id, name, account_type, plan)
values ('dededede-0000-0000-0000-0000000000aa', 'Northstar Agency (demo)', 'agency', 'pro')
on conflict (id) do update set name = excluded.name;

insert into identity.brands (id, account_id, name) values
    ('dededede-0000-0000-0000-0000000000b1', 'dededede-0000-0000-0000-0000000000aa', 'Aurora Beauty (client)'),
    ('dededede-0000-0000-0000-0000000000b2', 'dededede-0000-0000-0000-0000000000aa', 'Lumen Fitness (client)')
on conflict (id) do update set name = excluded.name;

-- Account-level roles.
--
-- account_role is intentionally NOT schema-qualified: enum types stayed in `public`
-- when the tables moved to context schemas, because they are shared vocabulary rather
-- than any one context's data. `public` is last on the search_path, so it resolves.
insert into identity.memberships (account_id, user_id, role)
select 'dededede-0000-0000-0000-0000000000aa', u.id,
       case
           when u.email like 'demo.admin%'    then 'ADMIN'::account_role
           when u.email like 'demo.manager%'  then 'MANAGER'::account_role
           when u.email like 'demo.marketer%' then 'MARKETER'::account_role
           when u.email like 'demo.analyst%'  then 'ANALYST'::account_role
           else 'FINANCE'::account_role
       end
from identity.users u
where u.email in (
    'demo.admin@northstar.test',
    'demo.manager@northstar.test',
    'demo.marketer@northstar.test',
    'demo.analyst@northstar.test',
    'demo.finance@northstar.test'
)
on conflict (account_id, user_id) do update set role = excluded.role;

-- Brand-level scoping.
--
-- ADMIN and FINANCE are account-wide roles and get no brand_access rows: an OWNER or
-- ADMIN implicitly reaches every brand. MANAGER, MARKETER and ANALYST are brand-scoped
-- and are pinned to Aurora only — which is what makes "cannot see Lumen" testable.
insert into identity.brand_access (membership_id, brand_id, role)
select m.id, 'dededede-0000-0000-0000-0000000000b1',
       case
           when u.email like 'demo.manager%'  then 'MANAGER'::account_role
           when u.email like 'demo.marketer%' then 'MARKETER'::account_role
           else 'ANALYST'::account_role
       end
from identity.memberships m
join identity.users u on u.id = m.user_id
where m.account_id = 'dededede-0000-0000-0000-0000000000aa'
  and u.email in (
      'demo.manager@northstar.test',
      'demo.marketer@northstar.test',
      'demo.analyst@northstar.test'
  )
on conflict (membership_id, brand_id) do nothing;

-- =============================================================
-- Report what was seeded
-- =============================================================
select u.email,
       a.name                as account,
       a.account_type,
       cast(m.role as text)  as account_role,
       coalesce(string_agg(b.name, ', '), '(all brands)') as reachable_brands
from identity.memberships m
join identity.users u    on u.id = m.user_id
join identity.accounts a on a.id = m.account_id
left join identity.brand_access ba on ba.membership_id = m.id
left join identity.brands b        on b.id = ba.brand_id
where m.account_id = 'dededede-0000-0000-0000-0000000000aa'
group by u.email, a.name, a.account_type, m.role
order by u.email;
