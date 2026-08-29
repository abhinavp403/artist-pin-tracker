# Execution plan — ArtistPin at scale

Derived from [`architecture-plan.html`](./architecture-plan.html). That document says *what* and *why*;
this one says *what to actually open a file and change, in what order*.

Ordering rule: **each ticket has to leave the app in a working, shippable state on its own.** Nothing here
requires the next ticket to land for the current one to be safe to merge. Where a ticket is blocked on a
decision only you can make (which backend, whether to pay for it yet), that's called out explicitly rather
than assumed.

Priority key: **P0** — do before anything else grows the user base. **P1** — required for "many users" to be
true at all. **P2** — required for the app to feel as good multi-user as it does today. **P3** — deferred,
not blocking.

---

## Milestone A — Stop the bleeding (P0)

No accounts, no backend, no schema change. Ships alone.

- [x] **A1. Stand up the smallest possible proxy for the Spotify token exchange.**
  A single serverless function (Cloudflare Worker, Vercel function, or a Cloud Run container — pick whichever
  you already have an account for) that holds `SPOTIFY_CLIENT_ID`/`SPOTIFY_CLIENT_SECRET` server-side and
  exposes two routes: `POST /spotify/search` and `GET /spotify/artist/{id}`. This is the one piece of new
  infrastructure that has to exist before A2 can happen — everything else in this milestone is client-side.
- [x] **A2. Point the client at the proxy instead of Spotify directly.**
  `SpotifyArtistImageSource`, `SpotifyArtistSearch` and `SpotifyTokenProvider`
  (`app/src/main/java/dev/abhinav/artistpin/data/SpotifyArtistImageSource.kt`,
  `ArtistSearch.kt`) currently call `api.spotify.com` with a bearer token minted on-device. Change the
  base URL in `Modules.kt` to the proxy's origin and delete `SpotifyTokenProvider` and the client
  id/secret entirely — the phone never needs to see them again.
- [x] **A3. Remove `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` from `local.properties` and `app/build.gradle.kts`'s `buildConfigField` calls.**
  Confirm with `./gradlew :app:assembleDebug` that nothing still references `BuildConfig.SPOTIFY_CLIENT_ID`.
- [x] **A4. Restrict `MAPS_API_KEY` in the Google Cloud Console.** Console work, no code.
  This key cannot follow the Spotify one to the server: the Maps SDK and the Places *Android* SDK
  (`com.google.android.libraries.places`, used by `PlacesVenueSearchService`) both read it on-device by
  design. Google's model for it is restriction rather than secrecy — a public key that only works when
  the caller proves it is your app.
  1. **Application restrictions → Android apps**, package `dev.abhinav.artistpin` plus the signing
     certificate's SHA-1. The debug keystore's is
     `36:58:EC:B8:5D:2E:81:F4:ED:B9:08:6B:0A:07:61:97:66:69:6D:F5` (fingerprints are not secret — they
     are extractable from any installed APK).
  2. **API restrictions → Restrict key**, allowing only *Maps SDK for Android* and *Places API*, so a
     leaked key cannot be spent on Geocoding or Directions.
  3. **A billing budget alert.** Maps requires billing enabled, and restriction is the only thing
     standing between a leaked key and someone else's usage on your card.

  **On publishing:** the release build is signed with a different certificate, so a second SHA-1 has to
  be added. With Play App Signing, Google re-signs the upload — the fingerprint to add is the one in
  *Play Console → Setup → App signing*, not the local release keystore. Getting this wrong is the
  classic "maps work in debug, grey tiles in production" bug.
- [ ] **A5. Do the same for the DICE importer's outbound requests, if abuse becomes a concern.**
  Lowest priority here — `DiceEventLinkImporter` calls a public, unauthenticated page and API, so
  there's no secret to leak. Revisit only if DICE starts rate-limiting your users' IPs collectively.

**Exit condition:** decompiling the APK no longer reveals a usable Spotify secret. Nothing else about the
app has changed for the user.

**Status — A1–A4 done and verified live.** The proxy lives in [`../server`](../server) on Vercel with the
Spotify credentials and `ARTISTPIN_API_KEY` set as environment variables; the client reads the proxy's
address from `API_BASE_URL`, sends the key on a dedicated OkHttp client, and no longer contains
`SpotifyTokenProvider`, `SpotifyAuthApi`, or either Spotify credential. The stale lines are out of
`local.properties`. `MAPS_API_KEY` is restricted to the app's package and debug SHA-1.

Verified rather than assumed:

- Grepping all 17 dex files of the built APK — the proxy URL is present, the client id and secret are not.
- Hitting the deployment: every route returns `200` with the key and `401` without it, on both
  `/spotify/search` and `/spotify/artist/{id}`; `/api/health` reports `spotifyConfigured: true` and
  `keyRequired: true`; search still returns the correct Bedouin first and the pinned-id route resolves
  Dixon.

**One bug found and fixed during A1.** The proxy originally set
`Cache-Control: public, s-maxage=86400` so repeat artist lookups would be served from Vercel's edge and
never reach Spotify. That cache sits *in front of* the function, so a cached response never ran the API-key
check: once any authorised request had warmed a query, anyone could replay that exact query with no key at
all. Confirmed live — an authorised request returned `200 MISS`, the identical unauthorised request
returned `200 HIT`. Responses are now `no-store`.

The lesson generalises to Milestone B: **caching has to live behind the authorisation check, not in front
of it.** A shared CDN cache in front of an authenticated endpoint is a way of serving other people's
authorised responses to unauthenticated callers. When the backend caches artist lookups for everyone
(architecture doc §02), it needs to be a store the handler reads *after* authorising — not a
`Cache-Control` header.

Consequence for now: identical artist lookups each reach Spotify. Fine at one user's volume, and the
proper fix belongs with the shared catalog in Milestone B.

Two deploy notes, both cost an hour the first time:

- `cd server && npx vercel --prod` **fails** with "Root Directory must be a relative path not starting with
  `./`". The setting is empty — the CLI infers it, because `server/` sits inside the git repo and it sends
  the path relative to the repo root as `./server`. Deploying from a copy outside any git repo works.
  Connecting the repo to Vercel with Root Directory `server` is the real fix.
- The Vercel MCP cannot see this project (`404`) even though the CLI deploys to it, so deploys have to go
  through the CLI.

---

## Milestone B — Backend foundation + accounts (P1)

**Backend decided: Supabase.** Postgres, bundled Auth, Row-Level Security that maps directly onto the
catalog/library split.

- [x] **B1. Create the Postgres schema** — a near-direct port of the existing Room tables:
  `artists`, `venues`, `cities` (catalog, no owner column) and `events`, `event_artist` (library, `user_id`
  column, foreign key to `auth.users`). Source of truth for the column shapes:
  `app/src/main/java/dev/abhinav/artistpin/core/database/Entities.kt`.
- [x] **B2. Wire up sign-in.** Add Google Sign-In (Play Services is already a dependency) or the backend's
  native auth SDK. New screen: a gate in front of `HomeRoute` in `ArtistPinNavHost.kt` that requires a
  session before showing the map.
- [x] **B3. Write the backend's find-or-create endpoints** for artist/venue/city — the server-side twin of
  `ArtistDao.findByName`, `ConcertDao.findCity`, `ConcertDao.findVenue`
  (`app/src/main/java/dev/abhinav/artistpin/core/database/ArtistDao.kt`,
  `ConcertDao.kt`). Same natural-key logic, just running against the shared table instead of one phone's copy.
- [x] **B4. Row-Level Security policies** (or equivalent): a user can only `SELECT`/`INSERT`/`UPDATE`/`DELETE`
  `events` and `event_artist` rows where `user_id = auth.uid()`; catalog tables are read-only to all
  authenticated users and write-only through the find-or-create endpoints (B3), never directly.
- [x] **B5. New network layer on the client**: a `BackendApi` Retrofit interface alongside the existing
  `SpotifyApi`/`DeezerApi`/`MusicBrainzApi` pattern in `Modules.kt`, plus a bearer-token interceptor that
  attaches the signed-in user's session token.
- [x] **B6. Swap `ConcertRepository`'s implementation, not its interface.** Every screen calls
  `repository.saveEvent()`, `observeCityPins()`, etc. — none of them know Room exists underneath. Write a
  new implementation of the same surface backed by `BackendApi` calls instead of `ConcertDao`/`ArtistDao`,
  and swap it in via the Koin module in `dataModule`. This is the ticket that proves the existing
  architecture was worth keeping clean.
- [x] **B7. One-time data migration**: reuse `BackupRepository`'s existing export shape
  (`app/src/main/java/dev/abhinav/artistpin/data/BackupRepository.kt`) as the payload for a
  `POST /import` endpoint that runs B3's find-or-create logic over every show in the file. This is how your
  own 50+ shows move onto the new backend without retyping them.

**Exit condition:** a fresh install requires sign-in; your existing device's data has been imported once via
B7; the app is fully functional online, single-device, for more than one account.

---

### The cutover, in order

B1–B7 are all written, but the switch has not been thrown. The order matters, because two of these steps
are only possible before it and one is only possible after.

1. **Run `supabase/migrations/0007_import.sql`**, then `verify.sql` — it adds a function, so check 6
   (`search_path` pinned) and check 7 (`anon` reaches nothing) both have something new to say.
2. **Build and sign in.** Room is still bound, so the app behaves exactly as it does today.
3. **Overflow → "Copy my shows to my account".** Reads the local library and replays it into Postgres.
   Nothing local is deleted, and it is safe to press twice — the import keys on the original event ids, so
   a second run reports everything as already present rather than duplicating 50+ shows.
4. **Check the row counts** in the Supabase dashboard against the message the app reported.
5. **Set `USE_BACKEND=true` in `local.properties` and rebuild.** This is the actual cutover.
6. **Only now**: sign in on a second account and confirm it sees none of your shows. This is the first
   real test of the RLS policies written back in B4 — everything up to here has been structural.

Room's copy is left untouched throughout, which is the rollback: setting the flag back to `false` returns
to the local library exactly as it was.

**Status — B7 written; the SQL has not been run and the cutover has not been performed.**
`import_backup(jsonb)` in `0007_import.sql`, plus `LibraryMigrator` and an overflow action on the client.
178 tests pass.

The payload is the app's **existing backup JSON, unchanged** — `BackupRepository.export()` already emits
every row with its foreign keys intact, which is exactly what an import needs, so there is no second
serialisation format to keep in step with the schema.

Two decisions worth recording:

- **Events keep their original ids; catalog rows do not.** Room generates event ids with
  `UUID.randomUUID()`, so they are already globally unique and carrying them over is what makes the import
  idempotent — the failure mode that matters is the instinct to re-run a half-finished migration. Cities,
  venues and artists go through find-or-create instead, because the shared catalog may already hold
  "Bedouin" under a different id, and that is the entire point of a shared catalog.
- **The function is SECURITY INVOKER.** It runs under the caller's RLS, so an import cannot write a row the
  user could not have written by hand — a payload naming someone else's event id simply produces no row,
  because the `exists` check can only see the caller's own events.

**The B6 gap is properly closed.** Backup and restore were briefly hidden under `USE_BACKEND`; they now
work against whichever store is bound, via a `LibraryBackup` interface with `RoomBackupRepository` and
`BackendBackupRepository` behind it. `export_backup()` (`0008_export.sql`) is the mirror of
`import_backup` and emits the identical on-disk JSON, so **one file format round-trips through either
implementation** — a file written by the Room build restores into the account, and vice versa. That
symmetry is what makes the backup an actual escape hatch rather than a backend-flavoured copy of one.

Losing file backup at the exact moment the data stops living on the device was backwards: that is when an
off-device copy is worth most, not least.

**Restore merges on the backend and replaces on Room**, and this is deliberate. Events keep their ids
through both export and import, so restoring a file taken from the same account is a *repair* — missing
rows return, present rows are skipped, nothing is destroyed. Implementing replace would mean deleting the
account's shows first, turning the fail-safe into the most dangerous button in the app and the one most
likely to be pressed in a panic. `LibraryBackup.restoreReplaces` carries the difference to the
confirmation dialog, which states which one is about to happen — promising replacement when it merges is a
lie, and wiping when the user expected a top-up is worse.

**Media does not migrate.** The import carries media *rows*; the files stay on the device. A photo is
visible on the phone that uploaded it and nowhere else until Milestone D moves the bytes to object
storage.

**Status — B1, B3 and B4 written; not yet run.** The migrations live in [`../supabase`](../supabase):
schema, RLS policies and grants, catalog find-or-create functions, library write functions, and the
aggregate views the map and artist tab read from. Nothing in the app references any of it yet, so this is
mergeable on its own and the app still runs entirely on Room.

**Run and audited.** All six migrations applied to the hosted project, and
[`../supabase/verify.sql`](../supabase/verify.sql) — a read-only audit of the properties that fail silently
— reports PASS across RLS, policy shape, `WITH CHECK` coverage, `security_invoker`, and `search_path`.

It caught one real bug on the first run: five views were still holding Supabase's default `anon` grants,
because `0002`'s `revoke … from anon` ran three files before the views in `0005` existed. Blocked twice over
in practice (the views are `security_invoker`, and every policy is scoped `to authenticated`), so no
disclosure — but a grant doing no work except waiting for one of those layers to slip. `0006` revokes it and
adds a default-privileges rule for future objects. **A `revoke` acts only on what exists when it runs**;
ordering is part of the security model. Re-run `verify.sql` after any migration that adds a table or view.

Still untested, because it needs two real accounts: that two signed-in users genuinely cannot see each
other's rows. Nothing reads these tables from the app until B6, so the earliest honest check is a second
account plus B7's import.

**Status — B2 written, not yet run on a device.** Google sign-in via Credential Manager and supabase-kt,
with the gate written as a branch *around* the NavHost rather than a route inside it: a route would leave
the whole graph — including a half-filled Add-a-show form — alive under the gate after sign-out, reachable
by back gesture and holding the departed account's data. Branching disposes the graph, so there is no back
stack to get wrong. `AuthState` keeps a distinct `Unknown` for "session still loading" (otherwise a cold
start flashes the sign-in screen at a signed-in user) and maps `RefreshFailure` to it rather than to
`SignedOut`, so opening the app in a venue with no signal does not eject the user — which matters more once
Milestone C makes the data local.

Console work: two OAuth clients are required, and **the app sends the *web* client id, not the Android
one**. The Android client must exist (Google won't mint a token without it) but is never named in code.
Setup steps and the nonce-hashing trap are in [`../supabase/README.md`](../supabase/README.md).

Note the app still runs entirely on Room — signing in gates the UI but changes no data path. That stays
true until B6.

**Status — B5 written.** `BackendApi` (interface), `SupabaseBackendApi`, DTOs and `BackendMappers`, in
`data/backend/`. Declared in Koin but not yet consumed by anything; 13 mapper tests cover the delimiter and
null cases. Build and full suite pass.

**Deviation: this is not Retrofit.** The ticket asked for "a `BackendApi` Retrofit interface … plus a
bearer-token interceptor". The interceptor half is the problem: an OkHttp `Interceptor` is synchronous, but
obtaining a valid token is not — access tokens expire hourly and must be refreshed over the network
*before* the call they authorise. Hand-writing it means either `runBlocking` on a refresh inside OkHttp's
thread pool, or attaching a stale token and handling the 401 on the way back, which makes every screen
tolerate a spurious auth failure an hour into a session. supabase-kt already does this correctly, and it
owns the session `AuthRepository` signs into — a second stack would mean two components owning one
session's lifetime. Retrofit still owns the three unauthenticated third-party APIs, unchanged.

**B6 is not the pure swap this plan assumed.** `ConcertRepository`'s surface is `Flow` throughout
(`observeCityPins`, `observeAllEvents`, …); PostgREST is request/response and has nothing to emit into
them. B6 needs either a refresh trigger (a `MutableSharedFlow` the writes poke, reads re-running per
emission) or Realtime subscriptions. The refresh trigger is the smaller change and is what Milestone C's
Room cache replaces anyway — at that point Room emits and the network only fills it. Worth deciding before
starting B6 rather than discovering mid-ticket.

**Status — B6 written and unit-tested; not yet run against the live project.** `ConcertRepository` is now
an interface with two implementations, `RoomConcertRepository` (the original, unchanged) and
`BackendConcertRepository`. Extracting the interface required no change above it — the ViewModels already
depended on exactly this surface, which is what the seam was for. 170 tests pass.

**Bound to Room by default, behind `USE_BACKEND` in `local.properties`.** Flipping it before B7 has run
would open the app on an empty map, since the existing shows are still in Room — which breaks this plan's
own rule that every ticket ships working. The flag is the cutover switch; B7 is what makes flipping it
safe.

The Flow problem from B5 is solved with a refresh trigger: a `MutableSharedFlow` every write pokes and
every read collects, so a save causes each open observer to re-fetch once. Chosen over Realtime because a
websocket held open all session to deliver events that only ever originate from this device is poor value,
and because Milestone C replaces the mechanism wholesale when Room returns as a cache and goes back to
being the thing that emits. **The accepted cost: a change made on another device does not appear until
something here triggers a refresh.** That is exactly the gap C's sync closes.

One subtlety worth keeping: **a failed read re-emits the previous value rather than propagating.** The
interface has no error channel on the read side (the Room implementation could not fail), so the
alternatives were cancelling the collector — which kills the screen — or emitting empty, which draws an
empty map and reads as *your shows are gone* rather than *we couldn't reach the server*. Three tests pin
this down, including that the held value does not become sticky once the connection returns.

**Known gap, and the first thing B7 must handle: `BackupRepository` still talks to the DAOs directly.**
Under `USE_BACKEND` the overflow menu's *Restore from a file* would write into Room, where nothing is
reading from — a silent no-op from the user's point of view. Export is still meaningful (it is the payload
B7 imports), but restore must be either routed through the repository interface or hidden while the
backend is bound.

Two smaller notes: there is deliberately **no `user_id` filter anywhere in the client** — RLS scopes every
query server-side, and a client-side filter would be a second home for the ownership rule, free to drift
from the policy that actually enforces it. And `artistsWithoutProfile` filters with `is null` rather than
`eq null`; SQL's null equals nothing, so an `eq` there returns an empty list and the artwork backfill
silently never runs.

Three behaviours could not be ported literally, because each was only correct while there was one user.
All three are documented in [`../supabase/README.md`](../supabase/README.md); the load-bearing one:

- **Orphan pruning had to be removed entirely.** `deleteOrphanArtists`/`deleteOrphanVenues`/
  `deleteOrphanCities` delete catalog rows nothing references. On a shared catalog, deleting your only
  Knockdown Center show would delete the venue and cascade into every other user's shows there. It is also
  a question the client cannot answer: RLS shows a user only their own events, so *every* venue looks
  orphaned. Unreferenced catalog rows now simply persist — invisible, tiny, and reused by the next person
  who types that name. **B6 must not reintroduce this**: the pruning calls in `ConcertRepository.deleteEvent`,
  `deleteArtist` and `saveEvent` have no backend equivalent and their absence is deliberate.

Two generalisable traps, both silent, both the same shape as Milestone A's cache bug — a layer that answers
*before* the authorisation check runs:

- **A Postgres view executes as its owner unless created `with (security_invoker = on)`**, which means RLS
  is never applied and every user reads every user's rows. The view works, returns data, and the data is
  other people's.
- **A `SECURITY DEFINER` function without a pinned `search_path`** lets the caller choose what code runs
  with the owner's privileges.

One deviation from B1 as sketched above: **`event_artists` has no `user_id` column.** Ownership is already
fully determined by the parent event, and a denormalised copy is a second source of truth that can drift
from it — at which point RLS enforces the wrong answer. The policies derive ownership through `event_id`.

Also decided while writing B1: `events` stores a real `date` rather than Room's epoch-day integer (SQLite
had no date type; Postgres does), and `artists.genres` deliberately stays a delimited string rather than a
`text[]`, because its NULL / `''` / values tri-state is what drives the artwork backfill and an array
column would collapse NULL and `'{}'` together into a permanent lookup loop.

---

## Milestone C — Offline-first sync (P2)

Blocked on Milestone B shipping — there's nothing to sync to until B6 exists.

- [x] **C1. Reintroduce Room as a cache**, not the source of truth: same `ArtistPinDatabase` schema, but every
  write also enqueues a row in a new `sync_outbox` table (`event_id`, `operation`, `payload_json`, `synced_at`).
- [x] **C2. `onSave()` in `EventEditViewModel` writes to Room first, then returns immediately** — the network
  call moves off the save's critical path entirely, matching fig. 3 in the architecture doc.
- [x] **C3. A `WorkManager` job (or foreground sync coroutine) that drains the outbox** whenever connectivity
  returns, calling B6's backend-backed repository methods and marking rows synced on success.
- [x] **C4. Conflict policy: last-write-wins per `event_id`.** Concerts aren't collaboratively edited, so no
  CRDT or operational-transform machinery is needed — document this explicitly so a future contributor
  doesn't over-engineer it.
- [x] **C5. Surface sync state in the UI** — a small indicator (dock overflow menu is the natural spot,
  next to the existing backup/restore items) showing "3 shows waiting to sync" when offline.

**Exit condition:** adding a show in a venue with no signal behaves exactly as it does today; it appears on
other devices once the phone reconnects.

**Status — C1, C2 and C4 written; C3 and C5 outstanding; not yet run on a device.**
`OfflineFirstConcertRepository` reads from Room and writes through it, queueing each change in a new
`sync_outbox` table (schema v7, migration 6→7, purely additive). `LibrarySync` drains the queue and then
pulls. 206 tests pass.

**The Flows are real again.** Room tells us when a table changed, so the refresh-trigger machinery
`BackendConcertRepository` had to invent is gone, and the map no longer blanks without signal.

**Sync model: push the outbox, pull by whole-library refresh.** The alternative — incremental two-way
sync — founders on identity: a venue created offline has a Room id the shared catalog has never seen,
because the catalog deduplicates by *name*, server-side. Merging incrementally would need an id-mapping
table, tombstones for deletes, and per-row merge rules. A full refresh (`export_backup` into the existing
restore path) sidesteps all of it, and leaves Room holding the *server's* ids so both sides agree by
construction. It costs a whole-library download — negligible at this size, worth revisiting in the
thousands of shows.

Two rules the tests pin down, because both fail silently rather than loudly:

- **A pull only ever runs with an empty outbox.** Refreshing over queued work would overwrite it with the
  server's older copy and destroy the very change waiting to be sent.
- **A failed push stops the drain rather than skipping past it.** The queue is ordered, and later entries
  assume earlier ones landed — an edit to a show whose creation never sent would simply be rejected.

**Queue entries supersede rather than accumulate.** Each payload is the complete draft, so editing one show
five times offline sends one save. Deleting a show drops everything queued for it, including its own
creation, rather than replaying a show only to delete it a moment later.

**`save_event` had to change** (`0009_save_event_upsert.sql`). A non-null event id used to mean "update this
existing row", but an offline save already has an id by the time it reaches the server — so every show
created offline would have failed to sync permanently while looking fine on the phone. The id is now just
the id: insert if new, update if ours, refuse if it belongs to someone else. That is also what makes replay
idempotent after an ambiguous failure.

**Media rows sync too**, though the files still do not. Without that, a full refresh would wipe local media
rows the server had never been told about.

**C3 and C5 are in.** `WorkManagerSyncScheduler` enqueues unique work constrained to `NetworkType.CONNECTED`,
which is the part that matters: sync used to run once when the signed-in graph composed, so a show added
underground stayed queued until the *next* launch even if the phone regained signal while the app sat open.
The worker now waits for connectivity and runs, including after the process has died.

Deliberate choices in the worker: `ExistingWorkPolicy.KEEP` rather than REPLACE, because work already
waiting drains the whole queue anyway and REPLACE would discard the accumulated backoff of a failing
request; exponential backoff from 30s; and `Result.retry()` when the queue is only partly drained, since
the drain stops at the first failure and a partial queue is a normal outcome rather than a finished one.
A non-network failure returns `failure()` — it will fail identically next time, so retrying it burns
battery for nothing.

The indicator states the backlog — "3 changes waiting to sync" — rather than a status word. That answers
the question someone actually has after adding shows with no signal; a tick does not. Tapping it syncs on
demand, for the case the worker cannot help with: standing somewhere with signal and wanting to know now.
`SyncScheduler` is an interface with a no-op implementation on the device-only build, which also keeps the
repository testable without WorkManager.


---

## Milestone D — Media to object storage (P2)

Independent of C — can be built in parallel once B ships.

- [ ] **D1. Replace `MediaImporter`'s local-copy behavior** with a presigned-upload flow: request an upload
  URL from the backend, `PUT` the file directly to object storage, store the resulting URL instead of a
  local file path.
- [ ] **D2. Thumbnailing** — either client-side before upload (cheap, works today) or a backend job
  triggered on upload (better for consistent thumbnail sizes across devices).
- [ ] **D3. Per-user storage quota**, enforced backend-side, surfaced in the UI before an upload is attempted
  rather than failing silently after.
- [ ] **D4. Migrate existing local photos** for accounts created via B7's import — walk the device's existing
  `EventMediaEntity` rows and upload each `localPath` file, same presigned flow as D1.

**Exit condition:** a photo added on one device is visible from another; uninstalling the app no longer loses
photos.

---

## Deferred — not scheduled

- **Anything social** (shared show pages, "who else was here") — explicitly out of scope until A–D are
  solid and there's an actual reason to build it. Not sized, not scheduled.
- **DICE-style import for other ticket sellers** (Tixr, Ticketmaster, Eventbrite) — separate from this
  scaling effort entirely; revisit only if asked for again.

---

## What stays exactly as it is

Called out so it isn't accidentally "fixed" during this work:

- **Artist sort preference** (`DataStoreSettingsStore`) stays device-local. It's a UI preference, not content.
- **`VenueSearchService`, `RemoteArtistSearch`, `ArtistImageSource`, `EventLinkImporter`, `DeviceLocationProvider`**
  are already clean interface boundaries with a single implementation each behind Koin. None of them need to
  change shape for any milestone above — B1–D4 only ever add or swap *implementations* behind interfaces that
  already exist.
