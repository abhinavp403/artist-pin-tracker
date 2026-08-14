-- One-time import of a device's local library (execution plan B7).
--
-- Takes the app's existing backup JSON — the exact shape `BackupRepository.export()` already
-- writes, unchanged — and replays it into the catalog/library split. That format was designed for
-- a file on disk, not for this, but it already carries every row with its foreign keys intact,
-- which is precisely what an import needs.
--
-- SECURITY INVOKER, deliberately. Everything here runs as the caller under the RLS policies in
-- 0002, so the import cannot write a row the caller could not have written by hand. The catalog
-- functions it calls are the SECURITY DEFINER ones from 0003, which is the only place elevated
-- rights are needed and the only place they are used.

create or replace function import_backup(payload jsonb)
returns jsonb
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_user       uuid := auth.uid();
    -- Local id -> catalog id. Room's ids for cities, venues and artists are meaningless here:
    -- the shared catalog may already hold "Bedouin" under a different id, and the whole point of
    -- the catalog is that it does. Only *events* keep their original ids; see below.
    v_cities     jsonb := '{}'::jsonb;
    v_venues     jsonb := '{}'::jsonb;
    v_artists    jsonb := '{}'::jsonb;
    r            jsonb;
    v_id         uuid;
    v_event_id   uuid;
    v_imported   integer := 0;
    v_skipped    integer := 0;
    v_artists_n  integer := 0;
    v_media      integer := 0;
begin
    if v_user is null then
        raise exception 'not signed in';
    end if;

    -- ---- Catalog: cities, then venues (which need city ids), then artists ------------------

    for r in select * from jsonb_array_elements(coalesce(payload -> 'cities', '[]'::jsonb))
    loop
        v_id := catalog_find_or_create_city(r ->> 'name', r ->> 'country', r ->> 'region');
        v_cities := jsonb_set(v_cities, array[r ->> 'id'], to_jsonb(v_id));
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'venues', '[]'::jsonb))
    loop
        -- A venue whose city is missing from the payload cannot be placed. Skipping it here means
        -- its events are skipped too, rather than landing at a null location.
        continue when v_cities ->> (r ->> 'cityId') is null;

        v_id := catalog_find_or_create_venue(
            r ->> 'name',
            (v_cities ->> (r ->> 'cityId'))::uuid,
            (r ->> 'latitude')::double precision,
            (r ->> 'longitude')::double precision,
            r ->> 'address'
        );
        v_venues := jsonb_set(v_venues, array[r ->> 'id'], to_jsonb(v_id));
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'artists', '[]'::jsonb))
    loop
        v_id := catalog_find_or_create_artist(r ->> 'name');
        v_artists := jsonb_set(v_artists, array[r ->> 'id'], to_jsonb(v_id));
        v_artists_n := v_artists_n + 1;

        -- Seeds the shared catalog with artwork already looked up on the device, so a fresh
        -- install does not re-fetch every portrait. Only fills blanks — catalog_set_artist_profile
        -- coalesces existing values first and cannot overwrite. A null `genres` is left alone so
        -- the artist stays in the backfill queue rather than being marked as looked-up-and-empty.
        if r ->> 'genres' is not null then
            perform catalog_set_artist_profile(
                v_id, r ->> 'imageUrl', r ->> 'genres', r ->> 'spotifyUrl'
            );
        end if;
    end loop;

    -- ---- Library: events keep their original ids ------------------------------------------
    --
    -- This is what makes the import idempotent. Room generates event ids with UUID.randomUUID(),
    -- so they are already globally unique and can be carried over verbatim. Running the import a
    -- second time then finds every event present and skips it, instead of producing a duplicate
    -- copy of the entire library — which is the failure mode that matters here, because the
    -- natural instinct after a half-finished import is to run it again.

    for r in select * from jsonb_array_elements(coalesce(payload -> 'events', '[]'::jsonb))
    loop
        v_event_id := (r ->> 'id')::uuid;

        continue when v_venues ->> (r ->> 'venueId') is null;

        -- Checked without a user_id predicate on purpose: RLS hides other users' events from this
        -- select, but the primary key is global. If a row with this id exists and is not visible
        -- here, the insert would fail on the key rather than silently merging into someone else's
        -- library, so skipping is both correct and the safe direction to be wrong in.
        if exists (select 1 from events where id = v_event_id) then
            v_skipped := v_skipped + 1;
            continue;
        end if;

        insert into events (id, user_id, venue_id, event_date, title, notes, rating)
             values (
                 v_event_id,
                 v_user,
                 (v_venues ->> (r ->> 'venueId'))::uuid,
                 -- Room stored an epoch day because SQLite has no date type; 0001 chose a real
                 -- date. This is the one place the two representations meet.
                 date '1970-01-01' + (r ->> 'dateEpochDay')::integer,
                 r ->> 'title',
                 r ->> 'notes',
                 (r ->> 'rating')::smallint
             );
        v_imported := v_imported + 1;
    end loop;

    -- ---- Lineups ---------------------------------------------------------------------------

    for r in select * from jsonb_array_elements(coalesce(payload -> 'eventArtists', '[]'::jsonb))
    loop
        continue when v_artists ->> (r ->> 'artistId') is null;

        insert into event_artists (event_id, artist_id, billing)
        select (r ->> 'eventId')::uuid,
               (v_artists ->> (r ->> 'artistId'))::uuid,
               case when r ->> 'billing' = 'SUPPORT' then 'SUPPORT'::billing
                    else 'HEADLINER'::billing end
        -- The exists is subject to the events SELECT policy, so it can only see the caller's own
        -- events. That is what keeps a payload naming someone else's event id from attaching an
        -- artist to their show — the insert simply produces no row.
         where exists (select 1 from events e where e.id = (r ->> 'eventId')::uuid)
        on conflict (event_id, artist_id) do nothing;
    end loop;

    -- ---- Media -----------------------------------------------------------------------------
    --
    -- Rows only; the files stay on the device. Until Milestone D moves the bytes to object
    -- storage, a photo imported here is visible on the phone that uploaded it and nowhere else.

    for r in select * from jsonb_array_elements(coalesce(payload -> 'media', '[]'::jsonb))
    loop
        insert into event_media (
            id, event_id, local_path, original_uri, mime_type, captured_at, sort_index
        )
        select (r ->> 'id')::uuid,
               (r ->> 'eventId')::uuid,
               r ->> 'localPath',
               r ->> 'originalUri',
               r ->> 'mimeType',
               case when r ->> 'capturedAt' is null then null
                    else to_timestamp((r ->> 'capturedAt')::bigint / 1000.0) end,
               coalesce((r ->> 'sortIndex')::integer, 0)
         where exists (select 1 from events e where e.id = (r ->> 'eventId')::uuid)
        on conflict (id) do nothing;

        -- FOUND reflects whether the insert above actually wrote a row, so a media entry whose
        -- event was skipped, or which was imported on a previous run, is not counted.
        if found then v_media := v_media + 1; end if;
    end loop;

    return jsonb_build_object(
        'events_imported', v_imported,
        'events_skipped', v_skipped,
        'artists_seen', v_artists_n,
        'media_imported', v_media
    );
end;
$$;

revoke execute on function import_backup(jsonb) from public;
grant execute on function import_backup(jsonb) to authenticated;
