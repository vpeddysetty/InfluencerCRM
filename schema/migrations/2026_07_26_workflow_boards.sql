-- =============================================================
-- Migration: Workflow boards + customizable stages
-- Date: 2026-07-26
-- Purpose:
--   Kanban-like boards for managing the brand-owner / marketing-owner
--   relationship with creators over a campaign lifecycle. Boards are NOT
--   tied to any campaign. Each board has a name, start/end date, and its own
--   ordered, customizable stages. A brand user may create up to 10 boards and
--   works on one at a time (is_active acts as the radio selection).
-- Notes:
--   - Idempotent by design (safe to re-run).
--   - The 10-board cap and single-active-board rule are enforced in the DAO.
-- =============================================================

create extension if not exists "pgcrypto";

create table if not exists workflow_boards (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    name        text not null,
    start_date  date,
    end_date    date,
    is_active   boolean not null default false,
    position    integer not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists idx_workflow_boards_user
    on workflow_boards(user_id, position);

create table if not exists workflow_board_stages (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references users(id) on delete cascade,
    board_id    uuid not null references workflow_boards(id) on delete cascade,
    stage_name  text not null,
    position    integer not null default 0,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index if not exists idx_workflow_board_stages_board
    on workflow_board_stages(board_id, position);

-- ---- trigger function --------------------------------------
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

-- ---- triggers ----------------------------------------------
do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_workflow_boards_updated') then
        create trigger trg_workflow_boards_updated
            before update on workflow_boards
            for each row execute function set_updated_at();
    end if;

    if not exists (select 1 from pg_trigger where tgname = 'trg_workflow_board_stages_updated') then
        create trigger trg_workflow_board_stages_updated
            before update on workflow_board_stages
            for each row execute function set_updated_at();
    end if;
end $$;
