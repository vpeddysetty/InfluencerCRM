-- =============================================================
-- Migration: FK + cleanup for accounts.legacy_user_id / brands.legacy_user_id
-- Date: 2026-08-02
-- Purpose:
--   Close a gap found while cleaning up end-to-end test fixtures.
--
--   Phase 1 added legacy_user_id to accounts and brands to correlate the backfill,
--   but added no foreign key. Deleting a user therefore left its account and brand
--   behind, pointing at an id that no longer exists. The Phase 1 reconciliation then
--   reports "account count != user count" — correctly, but for a reason that looks
--   like a backfill error rather than a dangling reference.
--
--   Two changes:
--     1) Delete already-orphaned accounts (their brands cascade).
--     2) Add ON DELETE SET NULL foreign keys so it cannot recur.
--
--   SET NULL, not CASCADE: an account may legitimately outlive the user it was
--   derived from. An agency owner leaving must not delete the agency and every
--   client brand with it. Nulling the correlation column loses only the Phase 1
--   backfill breadcrumb, which is exactly what should be dropped at that point.
--
-- Idempotent by design (safe to re-run).
-- =============================================================

-- =============================================================
-- 1) Remove accounts whose originating user is gone
-- =============================================================
-- Guarded: only accounts that still hold no domain data are removed. An orphan that
-- somehow acquired real rows is left in place and reported, because silently deleting
-- a tenant's data to satisfy a constraint would be far worse than a failed check.
do $$
declare
    orphans_with_data bigint;
    removed bigint;
begin
    select count(distinct a.id) into orphans_with_data
      from accounts a
      join brands b on b.account_id = a.id
     where a.legacy_user_id is not null
       and not exists (select 1 from users u where u.id = a.legacy_user_id)
       and (exists (select 1 from creators  c where c.brand_id = b.id)
         or exists (select 1 from campaigns c where c.brand_id = b.id));

    if orphans_with_data > 0 then
        raise warning
            'Skipping % orphaned account(s) that still hold creators or campaigns. '
            'Reassign or archive them deliberately rather than deleting here.',
            orphans_with_data;
    end if;

    with deletable as (
        select a.id
          from accounts a
         where a.legacy_user_id is not null
           and not exists (select 1 from users u where u.id = a.legacy_user_id)
           and not exists (
               select 1 from brands b
                where b.account_id = a.id
                  and (exists (select 1 from creators  c where c.brand_id = b.id)
                    or exists (select 1 from campaigns c where c.brand_id = b.id)))
    )
    delete from accounts a using deletable d where a.id = d.id;

    get diagnostics removed = row_count;
    raise notice 'Removed % orphaned account(s) left behind by deleted users.', removed;
end $$;

-- Any brand still naming a deleted user, whose account survived, keeps the brand but
-- drops the stale breadcrumb.
update brands b
   set legacy_user_id = null
 where b.legacy_user_id is not null
   and not exists (select 1 from users u where u.id = b.legacy_user_id);

update accounts a
   set legacy_user_id = null
 where a.legacy_user_id is not null
   and not exists (select 1 from users u where u.id = a.legacy_user_id);

-- =============================================================
-- 2) Add the foreign keys so this cannot recur
-- =============================================================
do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_accounts_legacy_user') then
        alter table accounts
            add constraint fk_accounts_legacy_user
            foreign key (legacy_user_id) references users(id) on delete set null;
    end if;

    if not exists (select 1 from pg_constraint where conname = 'fk_brands_legacy_user') then
        alter table brands
            add constraint fk_brands_legacy_user
            foreign key (legacy_user_id) references users(id) on delete set null;
    end if;
end $$;

-- =============================================================
-- 3) Post-conditions
-- =============================================================
do $$
declare
    dangling bigint;
begin
    select count(*) into dangling
      from accounts a
     where a.legacy_user_id is not null
       and not exists (select 1 from users u where u.id = a.legacy_user_id);
    if dangling > 0 then
        raise exception '% account(s) still reference a deleted user', dangling;
    end if;

    select count(*) into dangling
      from brands b
     where b.legacy_user_id is not null
       and not exists (select 1 from users u where u.id = b.legacy_user_id);
    if dangling > 0 then
        raise exception '% brand(s) still reference a deleted user', dangling;
    end if;

    raise notice 'legacy_user_id now has referential integrity on both accounts and brands.';
end $$;
