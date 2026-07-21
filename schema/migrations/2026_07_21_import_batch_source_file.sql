alter table import_batches
    add column if not exists source_file bytea;