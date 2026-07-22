alter table import_batches
    add column if not exists hydration_status text not null default 'discovered';

update import_batches
set hydration_status = 'discovered'
where hydration_status is null or btrim(hydration_status) = '';
