-- =============================================================
-- Migration: Workflow cards (campaign <-> creator relationship cards/tasks)
-- Date: 2026-07-26
-- Purpose:
--   A card associates a campaign to a creator and carries its own name plus
--   relationship attributes. Cards are the tasks placed on workflow boards.
--   A card starts unassigned (no board) and can be dragged onto a board and
--   into a stage (sets board_id + stage_id). Cards are independent of the
--   campaign_creators join table, so the same campaign+creator pair may appear
--   as multiple cards / on multiple boards.
-- Notes:
--   - Idempotent by design (safe to re-run).
--   - Deleting a board cascades its cards; deleting a stage nulls the card's
--     stage_id (card stays on the board, unplaced).
-- =============================================================

create extension if not exists "pgcrypto";

create table if not exists public.workflow_cards (
    id            uuid primary key default gen_random_uuid(),
    user_id       uuid not null references users(id) on delete cascade,
    campaign_id   uuid not null references campaigns(id) on delete cascade,
    creator_id    uuid not null references creators(id) on delete cascade,
    board_id      uuid references workflow_boards(id) on delete cascade,
    stage_id      uuid references workflow_board_stages(id) on delete set null,
    name          text not null,
    status        text not null default 'todo',
    agreed_fee    numeric(12,2),
    fee_currency  text not null default 'USD',
    notes         text,
    tags          jsonb not null default '[]'::jsonb,
    position      integer not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index if not exists idx_workflow_cards_user      on workflow_cards(user_id);
create index if not exists idx_workflow_cards_board     on workflow_cards(board_id, stage_id, position);
create index if not exists idx_workflow_cards_campaign  on workflow_cards(campaign_id);
create index if not exists idx_workflow_cards_creator   on workflow_cards(creator_id);

-- ---- trigger function --------------------------------------
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

-- ---- trigger -----------------------------------------------
do $$
begin
    if not exists (select 1 from pg_trigger where tgname = 'trg_workflow_cards_updated') then
        create trigger trg_workflow_cards_updated
            before update on workflow_cards
            for each row execute function set_updated_at();
    end if;
end $$;
