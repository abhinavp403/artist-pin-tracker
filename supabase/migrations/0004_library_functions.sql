-- Library writes (execution plan B3, the caller-owned half).
--
-- Unlike 0003 these are *not* SECURITY DEFINER. They run as the caller, under the RLS policies in
-- 0002, and that is exactly what you want: the policies are the security model, and a function
-- that bypassed them would be a second, weaker copy of it. All these functions add is atomicity —
-- a function body is one transaction, which is what ConcertDao's @Transaction gave locally.

-- ---------------------------------------------------------------------------
-- save_event — the port of ConcertRepository.saveEvent + ConcertDao.saveEvent
-- ---------------------------------------------------------------------------

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

    -- Mirrors the `require` calls at the top of ConcertRepository.saveEvent. Those stay in the
    -- client too — they are what turns a bad draft into a readable field error instead of a
    -- round trip — but the boundary that actually has to hold is this one.
    -- Trimmed up front so the billing test further down compares like with like. Doing it inside
    -- the loop instead meant testing a trimmed name for membership of the raw array, so a draft
    -- carrying " Bedouin " as headliner would fail to match itself and be filed as support.
    select coalesce(array_agg(lower(trim(t))), '{}')
      into v_heads
      from unnest(coalesce(p_artist_names, '{}')) as n(t)
     where trim(t) <> '';

    -- Checked against the cleaned list, not the raw one: an array of [""] has length 1 and would
    -- have satisfied a bare array_length check while producing a show with nobody on the bill.
    if coalesce(array_length(v_heads, 1), 0) = 0 then
        raise exception 'at least one artist is required';
    end if;

    v_city_id := catalog_find_or_create_city(p_city_name, p_country, p_region);
    v_venue_id := catalog_find_or_create_venue(
        p_venue_name, v_city_id, p_latitude, p_longitude, p_address
    );

    if p_event_id is null then
        insert into events (user_id, venue_id, event_date, title, notes, rating)
             values (v_user, v_venue_id, p_event_date,
                     nullif(trim(coalesce(p_title, '')), ''),
                     nullif(trim(coalesce(p_notes, '')), ''),
                     p_rating)
          returning id into v_event_id;
    else
        update events
           set venue_id   = v_venue_id,
               event_date = p_event_date,
               title      = nullif(trim(coalesce(p_title, '')), ''),
               notes      = nullif(trim(coalesce(p_notes, '')), ''),
               rating     = p_rating
         where id = p_event_id
        returning id into v_event_id;

        -- RLS turns "someone else's event" into zero rows updated rather than an error, so
        -- without this check editing another user's show would report success and change nothing.
        if v_event_id is null then
            raise exception 'no such event, or it is not yours';
        end if;
    end if;

    -- Cleared and rewritten rather than diffed, matching ConcertDao.saveEvent. Removing an artist
    -- from a lineup is otherwise invisible: an upsert-only path can add and change rows but has
    -- no way to express a deletion.
    delete from event_artists where event_id = v_event_id;

    foreach v_name in array (p_artist_names || coalesce(p_support_names, '{}'))
    loop
        v_name := trim(v_name);
        continue when v_name = '';

        -- distinctBy { it.lowercase() } from resolveArtists. The same artist listed as both
        -- headliner and support would otherwise violate the (event_id, artist_id) primary key and
        -- abort the whole save.
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

-- ---------------------------------------------------------------------------
-- Deletion — and the orphan pruning that has to stop happening
-- ---------------------------------------------------------------------------

-- ConcertRepository.deleteEvent and deleteArtist both finish by pruning: deleteOrphanArtists,
-- deleteOrphanVenues, deleteOrphanCities. On one device that was plainly correct — an artist you
-- had no shows for was a leaked row.
--
-- Ported literally to a shared catalog it becomes destructive. Deleting your only Knockdown Center
-- show would delete the venue, and with it every other user's shows there. There is no version of
-- that pruning which is safe, because "no events reference this venue" is a question the caller
-- cannot answer — RLS means they can only see their own events, so *every* venue looks orphaned
-- to them.
--
-- So the pruning is gone, and nothing replaces it. An unreferenced catalog row is now just a row:
-- invisible in the UI (every query joins through events), a few dozen bytes, and available to the
-- next user who types that name. If they ever need collecting, that is a periodic server-side job
-- with a global view — never a client-triggered cascade.

create or replace function delete_event(p_event_id uuid)
returns void
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_deleted uuid;
begin
    -- event_artists and event_media cascade from the events row; the catalog is left alone.
    delete from events where id = p_event_id returning id into v_deleted;

    if v_deleted is null then
        raise exception 'no such event, or it is not yours';
    end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- Artist rename and delete, rewritten as library operations
-- ---------------------------------------------------------------------------

-- Both of these were catalog mutations on one device: renameArtist ran UPDATE artists SET name,
-- deleteArtist ran DELETE FROM artists. Neither can survive contact with a shared catalog — your
-- "Nghtmare" -> "NGHTMRE" correction would rename the artist on every other user's map, and the
-- delete would remove them from other people's shows entirely.
--
-- The operations still exist and still do what you want; they just act on *your rows pointing at
-- the catalog* rather than on the catalog itself. Rename is find-or-create the correct name and
-- repoint your own event_artists at it — which, note, is precisely what the local merge branch of
-- renameArtist already did when the new name collided with an existing artist. The collision path
-- was the general case all along.

create or replace function rename_artist(p_artist_id uuid, p_new_name text)
returns uuid
language plpgsql
set search_path = public, pg_temp
as $$
declare
    v_target uuid;
    v_name   text := trim(p_new_name);
begin
    if v_name = '' then
        raise exception 'an artist needs a name';
    end if;

    v_target := catalog_find_or_create_artist(v_name);
    if v_target = p_artist_id then
        return v_target;
    end if;

    -- Only rows on the caller's own events move; RLS scopes the update for us. Any show of yours
    -- that already lists the target artist would collide on the primary key, so those rows are
    -- dropped instead of repointed — the merge, expressed as a delete.
    delete from event_artists
     where artist_id = p_artist_id
       and event_id in (select event_id from event_artists where artist_id = v_target);

    update event_artists set artist_id = v_target where artist_id = p_artist_id;

    return v_target;
end;
$$;

-- Counted before the confirmation dialog opens, so it can say exactly what will be lost —
-- the port of ConcertRepository.previewArtistDeletion. Scoped to the caller by RLS, so the
-- numbers describe your library rather than the catalog.
create or replace function preview_artist_deletion(p_artist_id uuid)
returns table (shows_affected integer, shows_deleted integer)
language sql
stable
set search_path = public, pg_temp
as $$
    select
        (select count(*)::integer from event_artists where artist_id = p_artist_id),
        (select count(*)::integer from events e
          where exists (select 1 from event_artists
                         where event_id = e.id and artist_id = p_artist_id)
            and not exists (select 1 from event_artists
                             where event_id = e.id and artist_id <> p_artist_id));
$$;

create or replace function delete_artist(p_artist_id uuid)
returns void
language plpgsql
set search_path = public, pg_temp
as $$
begin
    delete from event_artists where artist_id = p_artist_id;

    -- Shows left with nobody on the bill go too — deleteEventsWithoutArtists. The catalog artist
    -- row itself stays exactly where it is.
    delete from events e
     where e.user_id = auth.uid()
       and not exists (select 1 from event_artists where event_id = e.id);
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

revoke execute on function save_event(uuid, date, text, text, text, text, double precision, double precision, text, text[], text[], text, text, smallint) from public;
revoke execute on function delete_event(uuid) from public;
revoke execute on function rename_artist(uuid, text) from public;
revoke execute on function preview_artist_deletion(uuid) from public;
revoke execute on function delete_artist(uuid) from public;

grant execute on function save_event(uuid, date, text, text, text, text, double precision, double precision, text, text[], text[], text, text, smallint) to authenticated;
grant execute on function delete_event(uuid) to authenticated;
grant execute on function rename_artist(uuid, text) to authenticated;
grant execute on function preview_artist_deletion(uuid) to authenticated;
grant execute on function delete_artist(uuid) to authenticated;
