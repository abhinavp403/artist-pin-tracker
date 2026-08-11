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

- [ ] **A1. Stand up the smallest possible proxy for the Spotify token exchange.**
  A single serverless function (Cloudflare Worker, Vercel function, or a Cloud Run container — pick whichever
  you already have an account for) that holds `SPOTIFY_CLIENT_ID`/`SPOTIFY_CLIENT_SECRET` server-side and
  exposes two routes: `POST /spotify/search` and `GET /spotify/artist/{id}`. This is the one piece of new
  infrastructure that has to exist before A2 can happen — everything else in this milestone is client-side.
- [ ] **A2. Point the client at the proxy instead of Spotify directly.**
  `SpotifyArtistImageSource`, `SpotifyArtistSearch` and `SpotifyTokenProvider`
  (`app/src/main/java/dev/abhinav/artistpin/data/SpotifyArtistImageSource.kt`,
  `ArtistSearch.kt`) currently call `api.spotify.com` with a bearer token minted on-device. Change the
  base URL in `Modules.kt` to the proxy's origin and delete `SpotifyTokenProvider` and the client
  id/secret entirely — the phone never needs to see them again.
- [ ] **A3. Remove `SPOTIFY_CLIENT_ID` / `SPOTIFY_CLIENT_SECRET` from `local.properties` and `app/build.gradle.kts`'s `buildConfigField` calls.**
  Confirm with `./gradlew :app:assembleDebug` that nothing still references `BuildConfig.SPOTIFY_CLIENT_ID`.
- [ ] **A4. Do the same for the DICE importer's outbound requests, if abuse becomes a concern.**
  Lower priority than A1–A3 — `DiceEventLinkImporter` calls a public, unauthenticated page and API, so
  there's no secret to leak. Revisit only if DICE starts rate-limiting your users' IPs collectively.

**Exit condition:** decompiling the APK no longer reveals a usable Spotify secret. Nothing else about the
app has changed for the user.

---

## Milestone B — Backend foundation + accounts (P1)

**Decision needed before starting:** which backend (architecture doc §06 recommends Supabase — Postgres,
bundled Auth, Row-Level Security that maps directly onto the catalog/library split). Everything below
assumes that choice is made; the tickets are written generically enough to survive picking something else,
but the RLS-specific ones (B4) assume a Postgres-with-RLS backend specifically.

- [ ] **B1. Create the Postgres schema** — a near-direct port of the existing Room tables:
  `artists`, `venues`, `cities` (catalog, no owner column) and `events`, `event_artist` (library, `user_id`
  column, foreign key to `auth.users`). Source of truth for the column shapes:
  `app/src/main/java/dev/abhinav/artistpin/core/database/Entities.kt`.
- [ ] **B2. Wire up sign-in.** Add Google Sign-In (Play Services is already a dependency) or the backend's
  native auth SDK. New screen: a gate in front of `HomeRoute` in `ArtistPinNavHost.kt` that requires a
  session before showing the map.
- [ ] **B3. Write the backend's find-or-create endpoints** for artist/venue/city — the server-side twin of
  `ArtistDao.findByName`, `ConcertDao.findCity`, `ConcertDao.findVenue`
  (`app/src/main/java/dev/abhinav/artistpin/core/database/ArtistDao.kt`,
  `ConcertDao.kt`). Same natural-key logic, just running against the shared table instead of one phone's copy.
- [ ] **B4. Row-Level Security policies** (or equivalent): a user can only `SELECT`/`INSERT`/`UPDATE`/`DELETE`
  `events` and `event_artist` rows where `user_id = auth.uid()`; catalog tables are read-only to all
  authenticated users and write-only through the find-or-create endpoints (B3), never directly.
- [ ] **B5. New network layer on the client**: a `BackendApi` Retrofit interface alongside the existing
  `SpotifyApi`/`DeezerApi`/`MusicBrainzApi` pattern in `Modules.kt`, plus a bearer-token interceptor that
  attaches the signed-in user's session token.
- [ ] **B6. Swap `ConcertRepository`'s implementation, not its interface.** Every screen calls
  `repository.saveEvent()`, `observeCityPins()`, etc. — none of them know Room exists underneath. Write a
  new implementation of the same surface backed by `BackendApi` calls instead of `ConcertDao`/`ArtistDao`,
  and swap it in via the Koin module in `dataModule`. This is the ticket that proves the existing
  architecture was worth keeping clean.
- [ ] **B7. One-time data migration**: reuse `BackupRepository`'s existing export shape
  (`app/src/main/java/dev/abhinav/artistpin/data/BackupRepository.kt`) as the payload for a
  `POST /import` endpoint that runs B3's find-or-create logic over every show in the file. This is how your
  own 50+ shows move onto the new backend without retyping them.

**Exit condition:** a fresh install requires sign-in; your existing device's data has been imported once via
B7; the app is fully functional online, single-device, for more than one account.

---

## Milestone C — Offline-first sync (P2)

Blocked on Milestone B shipping — there's nothing to sync to until B6 exists.

- [ ] **C1. Reintroduce Room as a cache**, not the source of truth: same `ArtistPinDatabase` schema, but every
  write also enqueues a row in a new `sync_outbox` table (`event_id`, `operation`, `payload_json`, `synced_at`).
- [ ] **C2. `onSave()` in `EventEditViewModel` writes to Room first, then returns immediately** — the network
  call moves off the save's critical path entirely, matching fig. 3 in the architecture doc.
- [ ] **C3. A `WorkManager` job (or foreground sync coroutine) that drains the outbox** whenever connectivity
  returns, calling B6's backend-backed repository methods and marking rows synced on success.
- [ ] **C4. Conflict policy: last-write-wins per `event_id`.** Concerts aren't collaboratively edited, so no
  CRDT or operational-transform machinery is needed — document this explicitly so a future contributor
  doesn't over-engineer it.
- [ ] **C5. Surface sync state in the UI** — a small indicator (dock overflow menu is the natural spot,
  next to the existing backup/restore items) showing "3 shows waiting to sync" when offline.

**Exit condition:** adding a show in a venue with no signal behaves exactly as it does today; it appears on
other devices once the phone reconnects.

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
