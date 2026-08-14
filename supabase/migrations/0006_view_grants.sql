-- Fixes an ordering bug in 0002.
--
-- 0002 ends with `revoke all on <the six tables> from anon`. That worked for the tables, which
-- existed by then. It could not work for the views, because they are not created until 0005 —
-- three files later. Supabase's default privileges on the `public` schema then granted each new
-- view to `anon` as it was created, and nothing came along afterwards to take it back.
--
-- Caught by check 7 of verify.sql, which is the reason that check exists: the migrations all
-- reported success, and every structural property of the schema was correct. Only asking the
-- database what `anon` could actually reach surfaced it.
--
-- Impact was limited to exposure rather than disclosure. The views are `security_invoker = on`,
-- so an anon query runs with anon's rights and fails on the underlying tables, which anon has no
-- grant on; and every RLS policy is scoped `to authenticated`, so anon matches nothing regardless.
-- Two independent layers stood behind this one. That is precisely why it is worth fixing rather
-- than reasoning away — the grant was doing no work except waiting for one of them to slip.

revoke all on city_pins, venue_pins, event_summaries, artist_event_summaries, artist_summaries
    from anon;

-- Re-asserted rather than assumed: the blanket revoke above targets anon only, but stating what
-- authenticated holds keeps the whole access story readable in one place.
grant select on city_pins, venue_pins, event_summaries, artist_event_summaries, artist_summaries
    to authenticated;

-- The general fix, so the next object added to this schema does not repeat it. A revoke acts on
-- what exists at the moment it runs; this acts on what gets created later, which is the half that
-- was missing.
alter default privileges in schema public revoke all on tables from anon;
alter default privileges in schema public revoke all on functions from anon;

-- Caveat worth knowing: default privileges attach to the role that *creates* the object, not to
-- the schema globally. The two lines above cover objects created by the role running this
-- migration, which is the role every migration in this folder runs as. An object created by some
-- other role — a dashboard action, a different admin — would not be covered. verify.sql check 7
-- stays the backstop, and is worth re-running after any migration that adds a table or view.
