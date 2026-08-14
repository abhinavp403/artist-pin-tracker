-- Find-or-create for the shared catalog (execution plan B3).
--
-- The server-side twin of ConcertDao.findCity / findVenue and ArtistDao.findByName
-- (app/src/main/java/dev/abhinav/artistpin/core/database/ConcertDao.kt, ArtistDao.kt). Same
-- natural-key logic, running against one shared table instead of one phone's copy.
--
-- These are SECURITY DEFINER because 0002 gives clients no write access to the catalog at all.
-- That is the point of routing through here: the function holds the privilege, and uses it for
-- exactly one thing — return the id of the row for this name, inserting it only if nobody has.
--
-- `set search_path` is mandatory on every one of them. A SECURITY DEFINER function runs with the
-- owner's rights, so if an unqualified name inside the body could resolve to a table in a schema
-- the *caller* controls, the caller chooses what code runs as the owner. Pinning the path closes
-- that; pg_temp is listed last so a temp table can never shadow a real one.

-- ---------------------------------------------------------------------------

create or replace function catalog_find_or_create_city(
    p_name    text,
    p_country text,
    p_region  text default null
) returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_id      uuid;
    v_name    text := trim(p_name);
    v_country text := trim(p_country);
begin
    if v_name = '' or v_country = '' then
        raise exception 'a city needs both a name and a country';
    end if;

    -- Select-then-insert in a retry loop rather than a plain INSERT ... ON CONFLICT. Two people
    -- adding their first Toronto show at the same moment both find nothing, both insert, and one
    -- gets a unique violation — at which point the right answer is not to fail but to go back and
    -- read the row the winner just wrote. ON CONFLICT DO NOTHING would return no id at all here,
    -- and DO UPDATE would pointlessly rewrite a shared row on every single lookup.
    loop
        select id into v_id
          from cities
         where lower(name) = lower(v_name)
           and lower(country) = lower(v_country);

        exit when found;

        begin
            insert into cities (name, country, region)
                 values (v_name, v_country, nullif(trim(coalesce(p_region, '')), ''))
              returning id into v_id;
            exit;
        exception when unique_violation then
            -- Lost the race. Loop round; the select will find the winner's row.
        end;
    end loop;

    return v_id;
end;
$$;

-- ---------------------------------------------------------------------------

create or replace function catalog_find_or_create_venue(
    p_name      text,
    p_city_id   uuid,
    p_latitude  double precision,
    p_longitude double precision,
    p_address   text default null
) returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_id   uuid;
    v_name text := trim(p_name);
begin
    if v_name = '' then
        raise exception 'a venue needs a name';
    end if;

    loop
        select id into v_id
          from venues
         where lower(name) = lower(v_name)
           and city_id = p_city_id;

        -- Note what does *not* happen when the venue is found: its coordinates and address are
        -- left exactly as they are. The first person to add a venue fixes where it sits, and
        -- everyone else's Places result — which may be a slightly different pin for the same
        -- building — is discarded rather than allowed to nudge the marker on other people's maps.
        exit when found;

        begin
            insert into venues (name, city_id, latitude, longitude, address)
                 values (v_name, p_city_id, p_latitude, p_longitude,
                         nullif(trim(coalesce(p_address, '')), ''))
              returning id into v_id;
            exit;
        exception when unique_violation then
        end;
    end loop;

    return v_id;
end;
$$;

-- ---------------------------------------------------------------------------

create or replace function catalog_find_or_create_artist(p_name text)
returns uuid
language plpgsql
security definer
set search_path = public, pg_temp
as $$
declare
    v_id   uuid;
    v_name text := trim(p_name);
begin
    if v_name = '' then
        raise exception 'an artist needs a name';
    end if;

    loop
        select id into v_id from artists where lower(name) = lower(v_name);
        exit when found;

        begin
            -- Inserted with genres NULL, which is what enrols the new artist in the client's
            -- artwork backfill. Once the catalog is shared this happens once globally rather than
            -- once per user: the second person to see NGHTMRE gets the portrait immediately.
            insert into artists (name) values (v_name) returning id into v_id;
            exit;
        exception when unique_violation then
        end;
    end loop;

    return v_id;
end;
$$;

-- ---------------------------------------------------------------------------

-- The write half of ArtistDao.setProfile — the backfill in ConcertRepository.keepArtistArtworkFresh
-- calling home.
create or replace function catalog_set_artist_profile(
    p_artist_id   uuid,
    p_image_url   text,
    p_genres      text,
    p_spotify_url text
) returns void
language plpgsql
security definer
set search_path = public, pg_temp
as $$
begin
    update artists
       set image_url   = coalesce(image_url, p_image_url),
           genres      = coalesce(genres, p_genres),
           spotify_url = coalesce(spotify_url, p_spotify_url)
     where id = p_artist_id;
end;
$$;

-- Every coalesce above puts the *existing* value first, so this function can only ever fill a
-- blank — it can never overwrite. On one device that distinction did not exist, because the only
-- writer was you. On a shared catalog it is the difference between a backfill and a vandalism
-- primitive: without it, any client could POST an arbitrary image_url for any artist and change
-- the portrait on every other user's map.
--
-- It also preserves the tri-state that ArtistDao depends on. A lookup that genuinely finds no
-- genres writes '' rather than NULL, and because '' is not NULL the coalesce keeps it — so that
-- artist leaves the backfill queue for good instead of being re-queried on every launch by every
-- user forever.

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

-- SECURITY DEFINER functions are executable by PUBLIC on creation, which would include the
-- `anon` role — every one of these would be callable with no account at all. Revoke first, then
-- grant back only to signed-in users.
revoke execute on function catalog_find_or_create_city(text, text, text) from public;
revoke execute on function catalog_find_or_create_venue(text, uuid, double precision, double precision, text) from public;
revoke execute on function catalog_find_or_create_artist(text) from public;
revoke execute on function catalog_set_artist_profile(uuid, text, text, text) from public;

grant execute on function catalog_find_or_create_city(text, text, text) to authenticated;
grant execute on function catalog_find_or_create_venue(text, uuid, double precision, double precision, text) to authenticated;
grant execute on function catalog_find_or_create_artist(text) to authenticated;
grant execute on function catalog_set_artist_profile(uuid, text, text, text) to authenticated;
