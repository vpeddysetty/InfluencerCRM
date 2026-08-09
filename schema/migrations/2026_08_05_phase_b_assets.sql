-- =============================================================
-- Phase B: asset library
-- Date: 2026-08-05
-- Roadmap: docs/landing-page-builder-roadmap.md §5 Phase B
--
-- Purpose:
--   Somewhere to put images. Every visual feature needs it, and doing it now avoids
--   base64-in-JSONB — the shortcut that gets taken under deadline and is painful to
--   undo (roadmap Phase B rationale).
--
-- What is stored here and what is not:
--   This table holds METADATA ONLY. The bytes live in object storage behind
--   AssetStoragePort. Storing image bytes in Postgres would bloat the row store, make
--   backups enormous, and route every image request through the BFF — which is exactly
--   what B.3 ("images never proxied through the BFF") rules out.
--
-- Tenancy:
--   brand_id is NOT NULL and every query filters on it. An asset belongs to one brand,
--   full stop. There is no sharing model and deliberately so: a shared asset pool across
--   brands would be a cross-tenant leak of campaign material before it is public.
--
-- Idempotent: safe to re-run.
-- =============================================================

begin;

create table if not exists content.assets (
    id                 uuid primary key default gen_random_uuid(),

    brand_id           uuid not null,
    created_by_user_id uuid,

    -- The key within the storage bucket. Opaque to callers: they resolve a URL through
    -- the service rather than constructing one, so the layout can change without a
    -- migration and without breaking stored references.
    storage_key        text not null,

    -- What the user called it, for the picker. Distinct from storage_key, which is
    -- generated — two files named "hero.png" must not collide.
    file_name          text not null,

    content_type       text not null,
    size_bytes         bigint,

    -- Nullable: only known for images, and only once probed. A non-image asset legitimately
    -- has neither.
    width              integer,
    height             integer,

    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'uq_assets_storage_key') then
        alter table content.assets add constraint uq_assets_storage_key unique (storage_key);
    end if;
end $$;

-- The only listing query: this brand's assets, newest first.
create index if not exists idx_assets_brand_created
    on content.assets (brand_id, created_at desc);

comment on table content.assets is
    'Brand-scoped asset metadata. Bytes live in object storage behind AssetStoragePort; '
    'this table never holds image data.';
comment on column content.assets.storage_key is
    'Opaque key within the storage bucket. Callers resolve URLs through the service rather '
    'than building them, so the key layout can change without a migration.';

-- updated_at trigger, matching the convention used by the rest of the schema.
do $$
begin
    if exists (select 1 from pg_proc where proname = 'set_updated_at') then
        if not exists (select 1 from pg_trigger where tgname = 'trg_assets_updated_at') then
            create trigger trg_assets_updated_at before update on content.assets
                for each row execute function set_updated_at();
        end if;
    end if;
end $$;

commit;

-- ---------------------------------------------------------------
-- Verification (expect the table with 0 rows):
--   select count(*) from content.assets;
--
-- Rollback:
--   drop table if exists content.assets;
--   -- Safe on its own, but orphans any object already written to storage: the bucket is
--   -- not transactional with Postgres. Clear the bucket prefix too, or those objects are
--   -- unreferenced and invisible.
-- ---------------------------------------------------------------
