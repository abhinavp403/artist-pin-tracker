-- save_event has to accept an id it has never seen (execution plan C2).
--
-- Until now a null p_event_id meant "insert, I'll pick the id" and a non-null one meant "update
-- this existing row". Offline-first breaks that split: a show saved with no signal is written to
-- Room immediately, which means it *already has an id* by the time the queue reaches the server.
-- The old function took the update branch, found nothing, and raised 'no such event' — so every
-- show created offline would have failed to sync, permanently, while looking fine on the phone.
--
-- The id is now simply the id: insert it if it is new, update it if it is ours, refuse it if it
-- belongs to someone else. That also makes replay idempotent, which is what lets a queue entry be
-- retried after an ambiguous failure without risking a duplicate show.

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
    p_notes          text,
    p_rating         smallint
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

    insert into events (id, user_id, venue_id, event_date, title, notes, rating)
         values (
             v_event_id,
             v_user,
             v_venue_id,
             p_event_date,
             nullif(trim(coalesce(p_title, '')), ''),
             nullif(trim(coalesce(p_notes, '')), ''),
             p_rating
         )
    on conflict (id) do update
        set venue_id   = excluded.venue_id,
            event_date = excluded.event_date,
            title      = excluded.title,
            notes      = excluded.notes,
            rating     = excluded.rating;

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
