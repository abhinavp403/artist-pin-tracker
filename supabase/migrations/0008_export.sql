-- The mirror of import_backup: the caller's whole library, in the app's backup format.
--
-- Exists so "Back up to a file" keeps working once the app reads from Postgres rather than Room.
-- Losing file backup at the moment the data stops living on your own device is exactly backwards.
--
-- Emits the same JSON shape `BackupRepository.export()` produces on-device — camelCase keys, epoch
-- days, millisecond timestamps — so one file format round-trips through either implementation.
-- A file exported here restores through import_backup, and a file exported by the Room build
-- imports here. That symmetry is the point; it is what makes the backup an actual escape hatch
-- rather than a backend-flavoured copy of one.
--
-- SECURITY INVOKER, so RLS decides what "the caller's library" means. The catalog tables are
-- readable by any signed-in user, so those selects are narrowed by hand to the rows this user's
-- own events actually reference — otherwise a personal backup would contain every artist and
-- venue anyone had ever added.

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
        select v.* from venues v
         where v.id in (select venue_id from my_events)
    ),
    my_cities as (
        select c.* from cities c
         where c.id in (select city_id from my_venues)
    ),
    my_links as (
        select ea.* from event_artists ea
         where ea.event_id in (select id from my_events)
    ),
    my_artists as (
        select a.* from artists a
         where a.id in (select artist_id from my_links)
    ),
    my_media as (
        select m.* from event_media m
         where m.event_id in (select id from my_events)
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
            -- Back to the epoch day the on-disk format uses. 0001 chose a real `date` column;
            -- this is the return leg of the same conversion import_backup does on the way in.
            'dateEpochDay', (event_date - date '1970-01-01'),
            'title', title, 'notes', notes, 'rating', rating
        )) from my_events), '[]'::jsonb),

        'eventArtists', coalesce((select jsonb_agg(jsonb_build_object(
            'eventId', event_id, 'artistId', artist_id, 'billing', billing
        )) from my_links), '[]'::jsonb),

        'media', coalesce((select jsonb_agg(jsonb_build_object(
            'id', id, 'eventId', event_id,
            -- The file path as recorded. Still device-local until Milestone D, so a backup taken
            -- on one phone and restored on another brings the rows and no pictures — same as the
            -- on-device backup has always behaved.
            'localPath', coalesce(local_path, remote_url, ''),
            'originalUri', original_uri, 'mimeType', mime_type,
            'capturedAt', case when captured_at is null then null
                               else (extract(epoch from captured_at) * 1000)::bigint end,
            'sortIndex', sort_index
        )) from my_media), '[]'::jsonb)
    );
$$;

revoke execute on function export_backup() from public;
grant execute on function export_backup() to authenticated;
