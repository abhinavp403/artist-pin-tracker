-- Photo storage (execution plan D1).
--
-- Until now media rows synced but the files did not: a photo lived on exactly one phone, and
-- losing the device lost it. This adds the bucket the bytes go in, and the policies that make it
-- as private as the rest of the library.
--
-- **The bucket is not public.** A public bucket would be simpler — permanent URLs, free caching —
-- but a public URL is a bearer token that never expires: anywhere it leaks (a screenshot, a log,
-- a shared link) has the photo forever, and there is no revoking it short of deleting the file.
-- That would undo the thing Milestone B was for, where possession of a URL grants nothing and the
-- session decides. Reads go through short-lived signed URLs instead.

insert into storage.buckets (id, name, public, file_size_limit)
     values ('event-media', 'event-media', false, 52428800)
on conflict (id) do nothing;

-- 50 MB per object. Generous for a phone photo, and a ceiling on how much one bad request can
-- cost — quotas proper are D3.

-- ---------------------------------------------------------------------------
-- Policies
-- ---------------------------------------------------------------------------
--
-- Ownership is carried by the *path*: every object is stored as
--
--     {user_id}/{event_id}/{media_id}.{ext}
--
-- so `(storage.foldername(name))[1]` is the owner's id and every policy below compares it to
-- auth.uid(). This is the storage equivalent of the user_id column on `events` — the same rule,
-- expressed in the only place the storage API gives us to express it.
--
-- It also means the path is not a secret and does not need to be: knowing someone's object path
-- gets you a 403 without their session, exactly as knowing an event's id does.

create policy "users read their own media"
    on storage.objects for select
    to authenticated
    using (
        bucket_id = 'event-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

-- with check, not using, for insert — the same distinction as 0002. `using` filters rows that
-- already exist and therefore constrains nothing on an insert, which would let anyone write into
-- anyone's folder.
create policy "users upload into their own folder"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'event-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

-- Both clauses on update: `using` decides which objects may be targeted, `with check` decides what
-- they may become. Without the second, a user could move their own object into someone else's
-- folder.
create policy "users replace their own media"
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'event-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    )
    with check (
        bucket_id = 'event-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

create policy "users delete their own media"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'event-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
    );

-- ---------------------------------------------------------------------------
-- Where the file lives, recorded alongside the row
-- ---------------------------------------------------------------------------

-- The object's path, not a URL. Signed URLs expire, so storing one would mean storing something
-- that stops working — the path is the durable identifier and a URL is minted from it on demand.
alter table event_media add column if not exists storage_path text;

comment on column event_media.storage_path is
    'Path within the event-media bucket. Null means the file has not been uploaded yet, which is '
    'the normal state for a photo added offline and the backlog the sync works through.';

-- local_path stays. It is this device's copy and remains the fastest way to show a photo on the
-- phone that took it; storage_path is what makes the photo survive that phone.
