-- ArtistPin schema — the catalog/library split.
--
-- Ported from the Room entities in
-- app/src/main/java/dev/abhinav/artistpin/core/database/Entities.kt, with one structural idea
-- that Room had no need for: every table here is either *catalog* or *library*.
--
--   catalog  cities, venues, artists      shared by everyone, owned by nobody
--   library  events, event_artists,       one user's own rows, invisible to everyone else
--            event_media
--
-- The split is what makes the whole thing multi-tenant. Two people who saw the same show at
-- Knockdown Center point at one venue row and one artist row, but have entirely separate event
-- rows. It is also what the RLS policies in 0002 attach to: catalog is read-only to clients and
-- written only through the SECURITY DEFINER functions in 0003, library is readable and writable
-- only by its owner.

create extension if not exists "uuid-ossp";

-- ---------------------------------------------------------------------------
-- Catalog
-- ---------------------------------------------------------------------------

create table cities (
    id      uuid primary key default uuid_generate_v4(),
    name    text not null check (length(trim(name)) > 0),
    country text not null check (length(trim(country)) > 0),
    region  text
);

-- Room got case-insensitive natural keys from COLLATE NOCASE on the column. Postgres collations
-- are not case-insensitive by default, so the case-folding moves into the index itself — and the
-- find-or-create functions in 0003 must use exactly these expressions to hit it.
create unique index cities_natural_key on cities (lower(name), lower(country));

create table venues (
    id        uuid primary key default uuid_generate_v4(),
    name      text not null check (length(trim(name)) > 0),
    city_id   uuid not null references cities (id) on delete cascade,
    latitude  double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180),
    address   text
);

-- The lat/lng checks were runtime `require` calls in ConcertRepository.saveEvent. They belong
-- here too: the repository is no longer the only thing that can reach this table.
create unique index venues_natural_key on venues (lower(name), city_id);
create index venues_city_id_idx on venues (city_id);

create table artists (
    id          uuid primary key default uuid_generate_v4(),
    name        text not null check (length(trim(name)) > 0),
    image_url   text,
    -- Deliberately kept as the same delimited string the app already writes, not a text[].
    -- The column carries a tri-state that is load-bearing: NULL means "never looked up" and is
    -- what ArtistDao.observeArtistsWithoutProfile selects on to queue a backfill, whereas ''
    -- means "looked up, genuinely has none" and must never be re-queried. An array column would
    -- have blurred NULL and '{}' together and put every genre-less artist into a permanent
    -- lookup loop.
    genres      text,
    spotify_url text
);

create unique index artists_natural_key on artists (lower(name));

-- ---------------------------------------------------------------------------
-- Library
-- ---------------------------------------------------------------------------

create type billing as enum ('HEADLINER', 'SUPPORT');

create table events (
    id       uuid primary key default uuid_generate_v4(),
    user_id  uuid not null references auth.users (id) on delete cascade,
    venue_id uuid not null references venues (id) on delete restrict,
    -- Room stored an epoch day because SQLite has no date type. Postgres does, so this is a real
    -- date: it sorts, ranges and reads correctly in the dashboard. The client converts at the
    -- mapper boundary (LocalDate.parse / toString), which is the only place epoch days survive.
    event_date date not null,
    title    text,
    notes    text,
    rating   smallint check (rating between 1 and 5),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- `on delete restrict` above, not cascade: deleting a venue must never silently delete other
-- people's shows at it. Nothing deletes catalog rows anyway (see the orphan-pruning note in
-- 0004), so this is a backstop against a stray dashboard DELETE.

create index events_user_id_idx on events (user_id);
create index events_venue_id_idx on events (venue_id);
create index events_user_date_idx on events (user_id, event_date desc);

create table event_artists (
    event_id  uuid not null references events (id) on delete cascade,
    artist_id uuid not null references artists (id) on delete restrict,
    billing   billing not null default 'HEADLINER',
    primary key (event_id, artist_id)
);

-- No user_id column here, though the execution plan sketched one. Ownership is already fully
-- determined by the parent event, and a denormalised copy is a second source of truth that can
-- drift out of sync with it — at which point the RLS policy is enforcing the wrong answer. The
-- policies in 0002 derive ownership through event_id instead; events_user_id_idx and the primary
-- key make that lookup cheap.

create index event_artists_artist_id_idx on event_artists (artist_id);

create table event_media (
    id           uuid primary key default uuid_generate_v4(),
    event_id     uuid not null references events (id) on delete cascade,
    -- Storage-agnostic on purpose. Today the app holds a device-local path and that is all this
    -- column carries; Milestone D adds the object-storage URL alongside it and backfills. Having
    -- the table exist now means D is an additive change rather than a schema migration, and that
    -- B7's import can round-trip a backup without silently dropping its media rows.
    local_path   text,
    remote_url   text,
    original_uri text not null,
    mime_type    text not null,
    captured_at  timestamptz,
    sort_index   integer not null default 0,
    check (local_path is not null or remote_url is not null)
);

create index event_media_event_id_idx on event_media (event_id);

-- ---------------------------------------------------------------------------

create or replace function touch_updated_at() returns trigger
language plpgsql
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

-- Milestone C's sync needs a trustworthy "changed at" to resolve last-write-wins, and a client
-- is exactly the wrong thing to trust for it.
create trigger events_touch_updated_at
    before update on events
    for each row execute function touch_updated_at();
