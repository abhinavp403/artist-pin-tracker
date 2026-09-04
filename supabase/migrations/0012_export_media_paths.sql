-- Carries storage_path through the export/import round trip.
--
-- Without this, Milestone C's pull quietly undoes Milestone D. The pull is a whole-library refresh:
-- export_backup down, restore into Room. If the payload omits storage_path then every media row
-- comes back with no remote path, the backfill sees a library of photos it believes have never been
-- uploaded, and re-uploads all of them — on every sync, forever. The bytes would be re-sent
-- endlessly and nothing would ever look wrong on screen.

create or replace function export_backup()
returns jsonb
language sql
stable
set search_path = public, pg_temp
as $$
    with my_events as (
        select * from events where user_id = auth.uid()
    ),
    my_venues as (
        select v.* from venues v where v.id in (select venue_id from my_events)
    ),
    my_cities as (
        select c.* from cities c where c.id in (select city_id from my_venues)
    ),
    my_links as (
        select ea.* from event_artists ea where ea.event_id in (select id from my_events)
    ),
    my_artists as (
        select a.* from artists a where a.id in (select artist_id from my_links)
    ),
    my_media as (
        select m.* from event_media m where m.event_id in (select id from my_events)
    )
    select jsonb_build_object(
        'version', 1,
        'exportedAtEpochMillis', (extract(epoch from now()) * 1000)::bigint,

        'cities', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'name', name, 'country', country, 'region', region
        )) from my_cities), '[]'::jsonb),

        'venues', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'name', name, 'cityId', city_id,
            'latitude', latitude, 'longitude', longitude, 'address', address
        )) from my_venues), '[]'::jsonb),

        'artists', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'name', name, 'imageUrl', image_url,
            'genres', genres, 'spotifyUrl', spotify_url
        )) from my_artists), '[]'::jsonb),

        'events', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'venueId', venue_id,
            'dateEpochDay', (event_date - date '1970-01-01'),
            'title', title, 'notes', notes, 'rating', rating
        )) from my_events), '[]'::jsonb),

        'eventArtists', coalesce((select jsonb_agg(jsonb_build_object(
            'eventId', event_id, 'artistId', artist_id, 'billing', billing
        )) from my_links), '[]'::jsonb),

        'media', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'eventId', event_id,
            'localPath', coalesce(local_path, ''),
            -- The new field. A device that has never held this file gets a row it can still
            -- display, because the path is enough to sign a URL for.
            'storagePath', storage_path,
            'originalUri', original_uri, 'mimeType', mime_type,
            'capturedAt', case when captured_at is null then null
                               else (extract(epoch from captured_at) * 1000)::bigint end,
            'sortIndex', sort_index
        )) from my_media), '[]'::jsonb)
    );
$$;

revoke execute on function export_backup() from public;
grant execute on function export_backup() to authenticated;

-- ---------------------------------------------------------------------------

-- The other half of the round trip: a restored backup must be able to say where the bytes already
-- are, or importing a file exported after an upload would forget it.
create or replace function import_backup(payload jsonb)
returns jsonb
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_user       uuid := auth.uid();
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

    for r in select * from jsonb_array_elements(coalesce(payload -> 'cities', '[]'::jsonb))
    loop
        v_id := catalog_find_or_create_city(r ->> 'name', r ->> 'country', r ->> 'region');
        v_cities := jsonb_set(v_cities, array[r ->> 'id'], to_jsonb(v_id));
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'venues', '[]'::jsonb))
    loop
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

        if r ->> 'genres' is not null then
            perform catalog_set_artist_profile(
                v_id, r ->> 'imageUrl', r ->> 'genres', r ->> 'spotifyUrl'
            );
        end if;
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'events', '[]'::jsonb))
    loop
        v_event_id := (r ->> 'id')::uuid;
        continue when v_venues ->> (r ->> 'venueId') is null;

        if exists (select 1 from events where id = v_event_id) then
            v_skipped := v_skipped + 1;
            continue;
        end if;

        insert into events (id, user_id, venue_id, event_date, title, notes, rating)
             values (
                 v_event_id, v_user,
                 (v_venues ->> (r ->> 'venueId'))::uuid,
                 date '1970-01-01' + (r ->> 'dateEpochDay')::integer,
                 r ->> 'title', r ->> 'notes', (r ->> 'rating')::smallint
             );
        v_imported := v_imported + 1;
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'eventArtists', '[]'::jsonb))
    loop
        continue when v_artists ->> (r ->> 'artistId') is null;

        insert into event_artists (event_id, artist_id, billing)
        select (r ->> 'eventId')::uuid,
               (v_artists ->> (r ->> 'artistId'))::uuid,
               case when r ->> 'billing' = 'SUPPORT' then 'SUPPORT'::billing
                    else 'HEADLINER'::billing end
         where exists (select 1 from events e where e.id = (r ->> 'eventId')::uuid)
        on conflict (event_id, artist_id) do nothing;
    end loop;

    for r in select * from jsonb_array_elements(coalesce(payload -> 'media', '[]'::jsonb))
    loop
        insert into event_media (
            id, event_id, local_path, storage_path, original_uri, mime_type, captured_at, sort_index
        )
        select (r ->> 'id')::uuid,
               (r ->> 'eventId')::uuid,
               nullif(r ->> 'localPath', ''),
               r ->> 'storagePath',
               r ->> 'originalUri',
               r ->> 'mimeType',
               case when r ->> 'capturedAt' is null then null
                    else to_timestamp((r ->> 'capturedAt')::bigint / 1000.0) end,
               coalesce((r ->> 'sortIndex')::integer, 0)
         where exists (select 1 from events e where e.id = (r ->> 'eventId')::uuid)
        -- Updates the path on re-import rather than ignoring the row: a backup taken after an
        -- upload knows something the existing row may not.
        on conflict (id) do update set storage_path = coalesce(
            excluded.storage_path, event_media.storage_path
        );

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
