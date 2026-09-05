-- Phase 0 spike. Superseded by the real schema in Phase 1; migrations are forward-only,
-- so this file is never edited -- a later migration drops the table.
create table spike_records (
    id         uuid        primary key,
    note       text        not null,
    created_at timestamptz not null
);
