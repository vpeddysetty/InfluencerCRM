-- =============================================================
-- Mapping examples store for retrieval-augmented column mapping
-- Requires PostgreSQL + pgvector extension
-- =============================================================

create extension if not exists vector;

create table if not exists mapping_examples (
    id                 uuid primary key default gen_random_uuid(),
    user_id            uuid references users(id) on delete set null,
    template_name      text,
    source_signature   text not null,
    source_tab_names   text[] not null default '{}',
    source_columns     text[] not null default '{}',
    sample_values_json jsonb not null default '{}'::jsonb,
    mappings_json      jsonb not null default '{}'::jsonb,
    quality_score      numeric(4,3) not null default 0.700,
    usage_count        integer not null default 0,
    is_active          boolean not null default true,
    signature_embedding vector(1536),
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create index if not exists idx_mapping_examples_active
    on mapping_examples(is_active);

create index if not exists idx_mapping_examples_user
    on mapping_examples(user_id);

create index if not exists idx_mapping_examples_quality
    on mapping_examples(quality_score desc);

create index if not exists idx_mapping_examples_embedding_cos
    on mapping_examples using ivfflat (signature_embedding vector_cosine_ops)
    with (lists = 100);

do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_mapping_examples_updated') then
        create trigger trg_mapping_examples_updated
        before update on mapping_examples
        for each row execute function set_updated_at();
    end if;
end $$;
