-- Migration: bring the live `devices` table up to date with schema.sql.
--
-- The app was updated (unattended access, richer device status) but the live
-- Supabase database still had the older `devices` table, so device registration
-- failed with: "Could not find the 'unattended' column of 'devices'".
--
-- Safe to run more than once: every statement is IF NOT EXISTS.
-- Run it in the Supabase dashboard -> SQL Editor -> New query -> Run.

alter table public.devices add column if not exists platform           text default 'android';
alter table public.devices add column if not exists online             boolean not null default false;
alter table public.devices add column if not exists broadcasting       boolean not null default false;
alter table public.devices add column if not exists blocked            boolean not null default false;
alter table public.devices add column if not exists unattended         boolean not null default false;
alter table public.devices add column if not exists battery_level      int;
alter table public.devices add column if not exists charging           boolean;
alter table public.devices add column if not exists network_type       text;
alter table public.devices add column if not exists last_seen_at       timestamptz;
alter table public.devices add column if not exists last_screenshot    text;
alter table public.devices add column if not exists last_screenshot_at timestamptz;

-- Ask PostgREST to reload its schema cache immediately (otherwise it can take a
-- minute for the new columns to be visible to the API).
notify pgrst, 'reload schema';
