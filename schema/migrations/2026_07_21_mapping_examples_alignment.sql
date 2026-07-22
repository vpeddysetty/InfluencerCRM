-- =============================================================
-- Migration: Mapping examples table alignment
-- Date: 2026-07-21
-- Purpose:
--   1) Ensure mapping_examples exists for DAO CRUD endpoints
--   2) Align columns/defaults with MappingExample entity
--   3) Keep vector index support for retrieval workflows
-- Notes:
--   - Idempotent by design (safe to re-run)
-- =============================================================

create extension if not exists "pgcrypto";
create extension if not exists vector;

create table if not exists mapping_examples (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid references users(id) on delete set null,
    template_name       text,
    source_signature    text not null,
    source_tab_names    text[] not null default '{}',
    source_columns      text[] not null default '{}',
    sample_values_json  jsonb not null default '{}'::jsonb,
    mappings_json       jsonb not null default '{}'::jsonb,
    quality_score       numeric(4,3) not null default 0.700,
    usage_count         integer not null default 0,
    is_active           boolean not null default true,
    signature_embedding vector(1536),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);

alter table mapping_examples
    add column if not exists sample_values_json jsonb not null default '{}'::jsonb;

alter table mapping_examples
    add column if not exists usage_count integer not null default 0;

alter table mapping_examples
    add column if not exists signature_embedding vector(1536);

alter table mapping_examples
    alter column source_tab_names set default '{}';

alter table mapping_examples
    alter column source_columns set default '{}';

alter table mapping_examples
    alter column mappings_json set default '{}'::jsonb;

alter table mapping_examples
    alter column quality_score set default 0.700;

alter table mapping_examples
    alter column is_active set default true;

create index if not exists idx_mapping_examples_active
    on mapping_examples(is_active);

create index if not exists idx_mapping_examples_user
    on mapping_examples(user_id);

create index if not exists idx_mapping_examples_quality
    on mapping_examples(quality_score desc);

create index if not exists idx_mapping_examples_embedding_cos
    on mapping_examples using ivfflat (signature_embedding vector_cosine_ops)
    with (lists = 100);

create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_mapping_examples_updated') then
        create trigger trg_mapping_examples_updated
            before update on mapping_examples
            for each row execute function set_updated_at();
    end if;
end $$;
