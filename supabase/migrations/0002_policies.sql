-- Row-Level Security (execution plan B4).
--
-- The rule in one line: **catalog is readable by everyone and writable by nobody; library is
-- readable and writable only by its owner.**
--
-- Catalog writes are not blocked because clients never need them — they are blocked because a
-- client cannot be trusted to do them correctly. Find-or-create needs to read the whole table to
-- decide whether a row already exists, which is a privilege an ordinary caller should not hold
-- over data other people depend on. So the catalog is written exclusively through the
-- SECURITY DEFINER functions in 0003, which own that privilege and use it for one narrow purpose.
--
-- Every table below gets RLS enabled *and* at least one policy. Enabling RLS with no policy denies
-- everything, which is safe; adding a policy without enabling RLS is the dangerous half-step,
-- because the policy is simply never consulted and the table is wide open.

-- ---------------------------------------------------------------------------
-- Catalog — read-only to any signed-in user
-- ---------------------------------------------------------------------------

alter table cities  enable row level security;
alter table venues  enable row level security;
alter table artists enable row level security;

create policy "catalog cities are readable by authenticated users"
    on cities for select
    to authenticated
    using (true);

create policy "catalog venues are readable by authenticated users"
    on venues for select
    to authenticated
    using (true);

create policy "catalog artists are readable by authenticated users"
    on artists for select
    to authenticated
    using (true);

-- No insert/update/delete policies on the three tables above, deliberately. That is not an
-- omission to be filled in later: it is the entire protection. Without it, any signed-in user
-- could rename Knockdown Center, repoint an artist's Spotify URL, or delete a city out from under
-- every other user's shows.

-- ---------------------------------------------------------------------------
-- Library — owner-only, all four verbs
-- ---------------------------------------------------------------------------

alter table events        enable row level security;
alter table event_artists enable row level security;
alter table event_media   enable row level security;

create policy "users read their own events"
    on events for select
    to authenticated
    using (user_id = (select auth.uid()));

-- `with check` on insert, not `using`: `using` filters rows that already exist, so an INSERT
-- policy written with `using` alone constrains nothing and lets a user insert a row stamped with
-- someone else's user_id.
create policy "users create their own events"
    on events for insert
    to authenticated
    with check (user_id = (select auth.uid()));

-- Both clauses on update. `using` decides which rows may be targeted; `with check` decides what
-- they may become. With only `using`, a user could take one of their own events and reassign its
-- user_id to another account — handing over a row they no longer control.
create policy "users update their own events"
    on events for update
    to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

create policy "users delete their own events"
    on events for delete
    to authenticated
    using (user_id = (select auth.uid()));

-- event_artists and event_media have no user_id of their own; ownership comes from the parent
-- event, which is the only copy of that fact and therefore cannot disagree with itself.
--
-- The EXISTS is wrapped so it reads the same for all four verbs. Note it is itself subject to the
-- events SELECT policy above, so the subquery can only ever see the caller's own events — the
-- check is doubly closed.
create policy "users read artists on their own events"
    on event_artists for select
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_artists.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users add artists to their own events"
    on event_artists for insert
    to authenticated
    with check (exists (
        select 1 from events e
         where e.id = event_artists.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users update artists on their own events"
    on event_artists for update
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_artists.event_id
           and e.user_id = (select auth.uid())
    ))
    with check (exists (
        select 1 from events e
         where e.id = event_artists.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users remove artists from their own events"
    on event_artists for delete
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_artists.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users read media on their own events"
    on event_media for select
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_media.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users add media to their own events"
    on event_media for insert
    to authenticated
    with check (exists (
        select 1 from events e
         where e.id = event_media.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users update media on their own events"
    on event_media for update
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_media.event_id
           and e.user_id = (select auth.uid())
    ))
    with check (exists (
        select 1 from events e
         where e.id = event_media.event_id
           and e.user_id = (select auth.uid())
    ));

create policy "users remove media from their own events"
    on event_media for delete
    to authenticated
    using (exists (
        select 1 from events e
         where e.id = event_media.event_id
           and e.user_id = (select auth.uid())
    ));

-- ---------------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------------

-- RLS and grants are two independent gates and a request has to pass both. Policies alone grant
-- nothing — a table with perfect policies and no GRANT rejects every request — and grants alone
-- filter nothing. Stating both explicitly rather than relying on whatever default privileges the
-- project happens to carry means this file describes the whole access story on its own.

grant select on cities, venues, artists to authenticated;
grant select, insert, update, delete on events, event_artists, event_media to authenticated;

-- `anon` is the role attached to a request bearing only the publishable key, which ships inside
-- the APK and is therefore public. It has no business reaching any of this, and revoking is worth
-- doing explicitly: the catalog policies above are scoped `to authenticated`, so anon is already
-- shut out by policy, but a future policy written without a role clause would silently open it.
revoke all on cities, venues, artists, events, event_artists, event_media from anon;

-- This revoke covers the tables only, and deliberately says so: the views in 0005 do not exist
-- yet, so they cannot be named here and were left holding Supabase's default anon grants until
-- 0006 cleaned them up. See 0006 for the fix and the standing default-privileges rule.

-- `(select auth.uid())` rather than a bare `auth.uid()` throughout. The subquery form is evaluated
-- once per statement and cached; the bare call is re-evaluated per row, which on a full-table
-- select is one function call per row of yours in the table. Same semantics, materially different
-- cost once the library is more than a few dozen shows.
