-- The read side: ports of the aggregate queries in ConcertDao and ArtistDao.
--
-- These four views back the map markers, the show lists and the artist tab. They exist as views
-- rather than as client-side queries for the same reason they were @Query constants rather than
-- Kotlin: they are aggregations over four joined tables, and PostgREST's query syntax cannot
-- express them.
--
-- ===========================================================================
--  security_invoker = on, on every single view. This is not a tuning flag.
-- ===========================================================================
--
-- A Postgres view executes as its *owner* by default, not its caller. The owner here is the
-- superuser that ran the migration, so a view created without this option reads the underlying
-- tables with the owner's rights — and RLS is simply never applied. Every user querying
-- event_summaries would get every user's shows.
--
-- This is the same shape of bug as the CDN cache in front of the Spotify proxy: a layer that sits
-- in front of the authorisation check and answers before it runs. It is worth stating plainly
-- because the failure is silent and looks like success — the view works, returns rows, and the
-- rows are other people's.

-- ---------------------------------------------------------------------------

create view city_pins with (security_invoker = on) as
    select c.id,
           c.name,
           c.country,
           c.region,
           centroid.latitude,
           centroid.longitude,
           count(distinct e.id)::integer as event_count,
           count(distinct v.id)::integer as venue_count,
           max(e.event_date)             as last_event_date
      from cities c
      -- Each marker sits at the centroid of the venues actually visited, which is why the app has
      -- never needed to geocode a city. The subquery is scoped by the same RLS as the outer
      -- query, so the centroid is of *your* venues there — two users with different venues in
      -- Brooklyn correctly get slightly different pins.
      join (
            select v2.city_id,
                   avg(v2.latitude)  as latitude,
                   avg(v2.longitude) as longitude
              from venues v2
             where exists (select 1 from events e2 where e2.venue_id = v2.id)
             group by v2.city_id
           ) centroid on centroid.city_id = c.id
      join venues v on v.city_id = c.id
      join events e on e.venue_id = v.id
     group by c.id, c.name, c.country, c.region, centroid.latitude, centroid.longitude
     order by event_count desc, c.name asc;

-- ---------------------------------------------------------------------------

create view venue_pins with (security_invoker = on) as
    select v.id,
           v.name,
           v.city_id,
           v.latitude,
           v.longitude,
           v.address,
           count(e.id)::integer as event_count,
           max(e.event_date)    as last_event_date,
           -- The pin shown once zoomed in is the headliner of the most recent show at that venue,
           -- so both subqueries order by date descending and take one row.
           (select a.name from events e2
              join event_artists ea on ea.event_id = e2.id and ea.billing = 'HEADLINER'
              join artists a on a.id = ea.artist_id
             where e2.venue_id = v.id
             order by e2.event_date desc limit 1) as headliner_name,
           (select a.image_url from events e2
              join event_artists ea on ea.event_id = e2.id and ea.billing = 'HEADLINER'
              join artists a on a.id = ea.artist_id
             where e2.venue_id = v.id
             order by e2.event_date desc limit 1) as headliner_image_url
      from venues v
      join events e on e.venue_id = v.id
     group by v.id, v.name, v.city_id, v.latitude, v.longitude, v.address
     order by event_count desc, v.name asc;

-- ---------------------------------------------------------------------------

create view event_summaries with (security_invoker = on) as
    select e.id,
           e.event_date,
           e.title,
           e.rating,
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
           -- coalesce, not local_path alone: Milestone D moves media to object storage, and this
           -- keeps already-uploaded items rendering without touching the view again.
           (select coalesce(m.remote_url, m.local_path) from event_media m
             where m.event_id = e.id order by m.sort_index limit 1) as thumbnail_url
      from events e
      join venues v on v.id = e.venue_id
      join cities c on c.id = v.city_id
     order by e.event_date desc;

-- The app filters this three ways — by venue, by city, and by artist. The first two are plain
-- PostgREST filters on the columns above (`?venue_id=eq.…`, `?city_id=eq.…`), which is why
-- city_id is selected here even though no screen displays it. The by-artist case cannot be a
-- column filter, so it gets its own view.

create view artist_event_summaries with (security_invoker = on) as
    select ea.artist_id, s.*
      from event_summaries s
      join event_artists ea on ea.event_id = s.id;

-- ---------------------------------------------------------------------------

create view artist_summaries with (security_invoker = on) as
    select a.id,
           a.name,
           a.image_url,
           a.genres,
           a.spotify_url,
           count(distinct e.id)::integer as times_seen,
           (select string_agg(distinct c2.name, ',')
              from event_artists ea2
              join events e2 on e2.id = ea2.event_id
              join venues v2 on v2.id = e2.venue_id
              join cities c2 on c2.id = v2.city_id
             where ea2.artist_id = a.id) as city_names,
           min(e.event_date) as first_seen_date,
           max(e.event_date) as last_seen_date
      from artists a
      join event_artists ea on ea.artist_id = a.id
      join events e on e.id = ea.event_id
     group by a.id, a.name, a.image_url, a.genres, a.spotify_url
     order by times_seen desc, a.name asc;

-- Inner joins throughout, exactly as in ArtistDao.observeArtistSummaries. This is what keeps the
-- artist tab showing only artists *you* have seen rather than the entire shared catalog: an
-- artist with no events of yours has nothing to join to and drops out. Turning any of these into
-- a left join would quietly turn the artist tab into a global directory.

-- ---------------------------------------------------------------------------

grant select on city_pins, venue_pins, event_summaries, artist_event_summaries, artist_summaries
    to authenticated;
