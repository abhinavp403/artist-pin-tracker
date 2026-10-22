-- Drops `notes` and `rating` from events.
--
-- Both columns existed from 0001 and no release of the app ever wrote to either: there has never
-- been a field for a note or a rating on any screen. They were carried through Room, the backup
-- format, the sync payload, the views and these functions purely because they were declared once.
--
-- ORDER MATTERS. The app build published as 1.1 selects `rating` from event_summaries, so running
-- this before that build is replaced makes its list screens fail. Install the new version first,
-- then run this.
--
-- save_event keeps p_notes and p_rating as ignored, defaulted parameters rather than dropping
-- them. PostgREST resolves a function by the argument names in the request body, so a client that
-- still sends them would stop being able to save at all — writes from the old build keep working
-- this way, and the parameters can be removed once no such build is installed anywhere.

-- Views first: a column cannot be dropped while a view depends on it, and CREATE OR REPLACE VIEW
-- cannot remove a column from the select list.
drop view if exists artist_event_summaries;
drop view if exists event_summaries;

create view event_summaries with (security_invoker = on) as
    select e.id,
           e.event_date,
           e.title,
           v.id   as venue_id,
           v.name as venue_name,
           c.id   as city_id,
           c.name as city_name,
           (select string_agg(a.name, '||')
              from event_artists ea join artists a on a.id = ea.artist_id
             where ea.event_id = e.id and ea.billing = 'HEADLINER') as headliner_names,
           (select string_agg(a.name, '||')
              from event_artists ea join artists a on a.id = ea.artist_id
             where ea.event_id = e.id and ea.billing = 'SUPPORT')   as support_names,
           (select count(*)::integer from event_media m where m.event_id = e.id) as media_count,
           -- coalesce, not local_path alone: media moved to object storage in D1, and this
           -- keeps already-uploaded items rendering without touching the view again.
           (select coalesce(m.remote_url, m.local_path) from event_media m
             where m.event_id = e.id order by m.sort_index limit 1) as thumbnail_url
      from events e
      join venues v on v.id = e.venue_id
      join cities c on c.id = v.city_id
     order by e.event_date desc;

create view artist_event_summaries with (security_invoker = on) as
    select ea.artist_id, s.*
      from event_summaries s
      join event_artists ea on ea.event_id = s.id;

-- 0006 exists because a blanket revoke in 0002 ran before these views did; recreating them means
-- re-stating the grants, or anon regains the access that migration took away.
revoke all on event_summaries from anon;
revoke all on artist_event_summaries from anon;
grant select on event_summaries to authenticated;
grant select on artist_event_summaries to authenticated;

alter table events drop column notes;
alter table events drop column rating;

-- ---------------------------------------------------------------------------
-- save_event, with the two parameters accepted and ignored (see the note at the top).

create or replace function save_event(
    p_event_id       uuid,
    p_event_date     date,
    p_city_name      text,
    p_country        text,
    p_region         text,
    p_venue_name     text,
    p_latitude       double precision,
    p_longitude      double precision,
    p_address        text,
    p_artist_names   text[],
    p_support_names  text[],
    p_title          text,
    p_notes          text default null,
    p_rating         smallint default null
) returns uuid
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_user     uuid := auth.uid();
    v_city_id  uuid;
    v_venue_id uuid;
    v_event_id uuid;
    v_name     text;
    v_seen     text[] := '{}';
    v_artist   uuid;
    v_heads    text[];
    v_count    integer := 0;
begin
    if v_user is null then
        raise exception 'not signed in';
    end if;

    select coalesce(array_agg(lower(trim(t))), '{}')
      into v_heads
      from unnest(coalesce(p_artist_names, '{}')) as n(t)
     where trim(t) <> '';

    if coalesce(array_length(v_heads, 1), 0) = 0 then
        raise exception 'at least one artist is required';
    end if;

    v_city_id := catalog_find_or_create_city(p_city_name, p_country, p_region);
    v_venue_id := catalog_find_or_create_venue(
        p_venue_name, v_city_id, p_latitude, p_longitude, p_address
    );

    v_event_id := coalesce(p_event_id, gen_random_uuid());

    -- The ownership check has to come first and has to be explicit. RLS turns "someone else's
    -- event" into zero rows rather than an error, so an unguarded upsert would report success
    -- while changing nothing — and an unguarded insert would fail on the primary key with a
    -- message that gives away that the id exists.
    if exists (select 1 from events where id = v_event_id and user_id <> v_user) then
        raise exception 'that event belongs to someone else';
    end if;

    insert into events (id, user_id, venue_id, event_date, title)
         values (
             v_event_id,
             v_user,
             v_venue_id,
             p_event_date,
             nullif(trim(coalesce(p_title, '')), '')
         )
    on conflict (id) do update
        set venue_id   = excluded.venue_id,
            event_date = excluded.event_date,
            title      = excluded.title;

    -- Cleared and rewritten rather than diffed: removing an artist from a lineup is otherwise
    -- invisible, since an upsert-only path can add and change rows but cannot express a deletion.
    delete from event_artists where event_id = v_event_id;

    foreach v_name in array (p_artist_names || coalesce(p_support_names, '{}'))
    loop
        v_name := trim(v_name);
        continue when v_name = '';
        continue when lower(v_name) = any (v_seen);
        v_seen := v_seen || lower(v_name);

        v_artist := catalog_find_or_create_artist(v_name);

        insert into event_artists (event_id, artist_id, billing)
             values (v_event_id, v_artist,
                     case when lower(v_name) = any (v_heads) then 'HEADLINER'::billing
                          else 'SUPPORT'::billing end);
        v_count := v_count + 1;
    end loop;

    if v_count = 0 then
        raise exception 'at least one artist is required';
    end if;

    return v_event_id;
end;
$$;

revoke execute on function save_event(uuid, date, text, text, text, text, double precision, double precision, text, text[], text[], text, text, smallint) from public;
grant execute on function save_event(uuid, date, text, text, text, text, double precision, double precision, text, text[], text[], text, text, smallint) to authenticated;

-- ---------------------------------------------------------------------------
-- The backup round trip. Export stops emitting the two fields; import stops reading them, which
-- also means a file written before this migration still restores — the extra keys are ignored
-- rather than rejected, exactly as the app's own parser treats them.

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
            'title', title
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

        insert into events (id, user_id, venue_id, event_date, title)
             values (
                 v_event_id, v_user,
                 (v_venues ->> (r ->> 'venueId'))::uuid,
                 date '1970-01-01' + (r ->> 'dateEpochDay')::integer,
                 r ->> 'title'
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
