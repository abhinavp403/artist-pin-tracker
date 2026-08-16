-- Makes delete_event idempotent.
--
-- The original raised when it deleted nothing, which is the right answer for a UI action — a
-- delete that silently does nothing is worth complaining about. It is the wrong answer for a
-- replayed queue.
--
-- The case that broke: add a show while offline, then delete it before it ever syncs. The queued
-- creation is dropped (nothing on the server to create), but the deletion is still queued, and it
-- names an event the server has never seen. The old function raised, the drain stopped at that
-- entry, and because the entry could never succeed the entire outbox was blocked behind it
-- permanently — every later change piling up while the app reported "N changes waiting to sync"
-- and never made progress.
--
-- Deleting something that is already absent is the desired end state, not an error.
--
-- The signature is deliberately unchanged. Returning whether a row was actually removed would be
-- mildly useful, but Postgres will not let `create or replace` change a return type — it would need
-- `drop function` first, and a drop leaves a window in which the app's delete does not exist at all
-- and its grant has to be re-applied. No caller reads the result, so the nicety is not worth
-- handing this migration a way to fail halfway.

create or replace function delete_event(p_event_id uuid)
returns void
language plpgsql
set search_path = public, pg_temp
as $$
begin
    -- event_artists and event_media cascade from the events row; the catalog is left alone.
    -- No `raise` when nothing matched: RLS makes "already deleted" and "not yours" look identical
    -- from here, and refusing either one is what stopped the queue. A replayed delete is a no-op.
    delete from events where id = p_event_id;
end;
$$;

revoke execute on function delete_event(uuid) from public;
grant execute on function delete_event(uuid) to authenticated;
