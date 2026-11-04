-- RemoteSupport — Supabase schema
-- Run in the Supabase SQL editor (or `supabase db push`).
-- Every row is owned by a user (owner_id = auth.uid()) and protected by RLS.

-- ---------------------------------------------------------------------------
-- Enums
-- ---------------------------------------------------------------------------
create type session_status as enum ('pending', 'active', 'ended', 'abandoned');
create type session_mode   as enum ('control', 'view');
create type sub_status     as enum ('trial', 'active', 'past_due', 'canceled');
create type txn_status      as enum ('pending', 'success', 'failed');

-- ---------------------------------------------------------------------------
-- profiles (1:1 with auth.users)
-- ---------------------------------------------------------------------------
create table public.profiles (
    id            uuid primary key references auth.users (id) on delete cascade,
    display_name  text,
    phone_number  text,
    created_at    timestamptz not null default now()
);

-- Create a profile row automatically on signup.
create function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
    insert into public.profiles (id, display_name) values (new.id, new.email);
    insert into public.subscriptions (owner_id, status, trial_ends_at)
        values (new.id, 'trial', now() + interval '14 days');
    return new;
end; $$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------
create table public.devices (
    id                 uuid primary key default gen_random_uuid(),
    owner_id           uuid not null references auth.users (id) on delete cascade,
    device_code        text not null,
    name               text not null,
    model              text,
    manufacturer       text,
    platform           text default 'android',
    os_version         text,
    app_version        text,
    online             boolean not null default false,
    broadcasting       boolean not null default false,
    blocked            boolean not null default false,
    -- Unattended access: owner has enrolled this device so a trusted controller may
    -- reconnect without a per-session accept. The PIN that gates it is NEVER stored
    -- server-side; only its salted hash lives on the target device itself.
    unattended         boolean not null default false,
    battery_level      int,
    charging           boolean,
    network_type       text,
    last_seen_at       timestamptz,
    last_screenshot    text,
    last_screenshot_at timestamptz,
    created_at         timestamptz not null default now(),
    unique (owner_id, device_code)
);
create index devices_owner_idx on public.devices (owner_id);

-- ---------------------------------------------------------------------------
-- device_pairs (controller <-> target pairing within one account)
-- ---------------------------------------------------------------------------
create table public.device_pairs (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null references auth.users (id) on delete cascade,
    device_a   uuid not null references public.devices (id) on delete cascade,
    device_b   uuid not null references public.devices (id) on delete cascade,
    created_at timestamptz not null default now(),
    unique (owner_id, device_a, device_b)
);

-- ---------------------------------------------------------------------------
-- remote_sessions
-- ---------------------------------------------------------------------------
create table public.remote_sessions (
    id                uuid primary key default gen_random_uuid(),
    owner_id          uuid not null references auth.users (id) on delete cascade,
    controller_device uuid not null references public.devices (id) on delete cascade,
    target_device     uuid not null references public.devices (id) on delete cascade,
    mode              session_mode not null default 'control',
    status            session_status not null default 'pending',
    started_at        timestamptz,
    end_reason        text,
    created_at        timestamptz not null default now()
);
create index sessions_owner_idx on public.remote_sessions (owner_id, status);

-- ---------------------------------------------------------------------------
-- signals (WebRTC signaling relay: offer / answer / ice / bye)
-- ---------------------------------------------------------------------------
create table public.signals (
    id               uuid primary key default gen_random_uuid(),
    owner_id         uuid not null references auth.users (id) on delete cascade,
    session_id       uuid not null references public.remote_sessions (id) on delete cascade,
    sender_device    uuid not null references public.devices (id) on delete cascade,
    recipient_device uuid not null references public.devices (id) on delete cascade,
    kind             text not null,  -- offer | answer | ice | bye
    payload          text not null,
    created_at       timestamptz not null default now()
);
create index signals_recipient_idx on public.signals (recipient_device, created_at);

-- ---------------------------------------------------------------------------
-- subscriptions
-- ---------------------------------------------------------------------------
create table public.subscriptions (
    id                 uuid primary key default gen_random_uuid(),
    owner_id           uuid not null unique references auth.users (id) on delete cascade,
    status             sub_status not null default 'trial',
    trial_ends_at      timestamptz,
    current_period_end timestamptz,
    updated_at         timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- mpesa_transactions (STK push billing)
-- ---------------------------------------------------------------------------
create table public.mpesa_transactions (
    id                  uuid primary key default gen_random_uuid(),
    owner_id            uuid not null references auth.users (id) on delete cascade,
    phone_number        text not null,
    amount              numeric(10,2) not null,
    checkout_request_id text,
    status              txn_status not null default 'pending',
    result_desc         text,
    created_at          timestamptz not null default now()
);
create index mpesa_owner_idx on public.mpesa_transactions (owner_id, created_at);

-- ---------------------------------------------------------------------------
-- audit_logs & feedback
-- ---------------------------------------------------------------------------
create table public.audit_logs (
    id         uuid primary key default gen_random_uuid(),
    owner_id   uuid not null references auth.users (id) on delete cascade,
    event      text not null,
    detail     text,
    created_at timestamptz not null default now()
);

create table public.feedback (
    id           uuid primary key default gen_random_uuid(),
    owner_id     uuid not null references auth.users (id) on delete cascade,
    contact_info text,
    message      text not null,
    created_at   timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Row Level Security — owner-scoped everywhere
-- ---------------------------------------------------------------------------
alter table public.profiles           enable row level security;
alter table public.devices            enable row level security;
alter table public.device_pairs       enable row level security;
alter table public.remote_sessions    enable row level security;
alter table public.signals            enable row level security;
alter table public.subscriptions      enable row level security;
alter table public.mpesa_transactions enable row level security;
alter table public.audit_logs         enable row level security;
alter table public.feedback           enable row level security;

create policy "own profile" on public.profiles
    for all using (auth.uid() = id) with check (auth.uid() = id);

-- Generic owner policy for the owner_id tables.
do $$
declare t text;
begin
    foreach t in array array[
        'devices','device_pairs','remote_sessions','signals',
        'audit_logs','feedback'
    ] loop
        execute format(
            'create policy %I on public.%I for all
             using (auth.uid() = owner_id) with check (auth.uid() = owner_id);',
            t || '_owner', t);
    end loop;
end $$;

-- Billing state is read-only for clients: only the signup trigger and the M-Pesa
-- edge functions (service role) may write it, or users could activate themselves.
create policy subscriptions_owner_read on public.subscriptions
    for select using (auth.uid() = owner_id);
create policy mpesa_transactions_owner_read on public.mpesa_transactions
    for select using (auth.uid() = owner_id);

-- ---------------------------------------------------------------------------
-- Realtime
-- ---------------------------------------------------------------------------
alter publication supabase_realtime add table
    public.devices, public.device_pairs, public.remote_sessions,
    public.signals, public.subscriptions, public.mpesa_transactions;

-- ---------------------------------------------------------------------------
-- Storage bucket for device screenshots (private; RLS by owner folder)
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public) values ('screenshots', 'screenshots', false)
    on conflict (id) do nothing;

create policy "own screenshots read" on storage.objects for select
    using (bucket_id = 'screenshots' and owner = auth.uid());
create policy "own screenshots write" on storage.objects for insert
    with check (bucket_id = 'screenshots' and owner = auth.uid());
create policy "own screenshots update" on storage.objects for update
    using (bucket_id = 'screenshots' and owner = auth.uid());

-- ---------------------------------------------------------------------------
-- Migration for databases created before the unattended-access feature.
-- Safe to run repeatedly; no-op once the column exists.
-- ---------------------------------------------------------------------------
alter table public.devices add column if not exists unattended boolean not null default false;
