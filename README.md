# ArtistPin

An Android app for tracking concerts on a map: cities and venues you've been to, the
artists you saw there and when, and the photos from each night — all pinned to the place it
happened.

## What it does

- **A map-first home screen.** Cities you've attended shows in appear as pins; zoom in and each
  pin becomes the artist you saw at that venue, drawn from Spotify artwork.
- **Add a show** by searching for artists and a venue — typing that could be picked is never
  required — or paste a [DICE](https://dice.fm) ticket link and the form fills itself in.
- **An artist screen** per act: every time you've seen them, genres, a Spotify link, and the gaps
  between shows.
- **A show screen**, styled as a ticket stub, with the lineup and a photo grid pulled from your
  gallery.
- **An account.** Sign in with Google and your shows live in Postgres rather than on one phone.
  Artists, venues and cities are a catalog everyone shares; your shows are yours alone, enforced
  by row-level security rather than by the app asking nicely.
- **It works with no signal.** Reads come from a local database, so the map and your shows are
  there underground. A save lands locally and returns immediately; the change queues and is sent
  when connectivity comes back, with the dock showing how many are waiting.
- **A stats screen** — "Your Stats", from the counts pill or the menu. Shows per year as a chart
  of one tile per show, the month you go out most, a treemap of the venues you keep returning to,
  and a passport stamp per country. The charts open what they describe: a venue box unfolds that
  venue's shows, a stamp frames that country on the map.
- **Backup and restore** to a JSON file — one format that round-trips through either store, so a
  file written before the migration restores into the account and vice versa.
- **Photos are backed up** to private object storage and served through short-lived signed URLs, so
  a new phone shows them after signing in. **Videos are not**: they routinely exceed the 50 MB
  object limit and would consume most of the storage quota, so they stay on the device that took
  them.

## Stack

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **DI:** Koin
- **Backend:** Supabase — Postgres, Auth, row-level security, Storage ([`supabase/`](supabase/))
- **Persistence:** Room (SQLite) as the local source of truth, syncing to Supabase through an
  outbox; Jetpack DataStore for preferences
- **Sync:** WorkManager, constrained to connectivity
- **Auth:** Credential Manager + Google ID tokens, via supabase-kt
- **Networking:** Retrofit + OkHttp + kotlinx.serialization; supabase-kt for the backend
- **Maps & places:** Google Maps Compose, Places SDK
- **Artist metadata:** Spotify Web API (images, search), MusicBrainz (genres — Spotify dropped
  this from its public API), Deezer (portrait fallback)
- **Media:** Coil 3, Media3 (video playback)
- **Tests:** JUnit, Robolectric, Turbine, Koin's compile-time graph verification

## Building it

Create `local.properties` in the project root (already gitignored) with:

```properties
MAPS_API_KEY=your-google-maps-and-places-key

# Sign-in. See supabase/README.md — GOOGLE_WEB_CLIENT_ID is the *web* OAuth client, not the
# Android one, even though an Android client also has to exist.
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-publishable-anon-key
GOOGLE_WEB_CLIENT_ID=your-web-oauth-client-id

# Which store the app reads. Defaults to false (Room, device-local); set true once
# supabase/ has been migrated and your library uploaded.
USE_BACKEND=false

# Optional — both have working defaults
API_BASE_URL=https://your-own-proxy.vercel.app/api/
ARTISTPIN_API_KEY=matches-ARTISTPIN_API_KEY-on-the-proxy

# Debug builds only. Skips the sign-in gate and seeds the library from
# app/src/debug/assets/dev-library.json — an exported backup you drop there yourself — so an
# emulator can show real screens without an account. The release build compiles this as a
# literal false, so nothing here can switch it on in something shippable.
DEV_SKIP_AUTH=false
```

- **Supabase URL and key:** Settings → Data API in your project. The publishable key is meant to
  ship in the client; row-level security is what makes that safe. **Never** the service role key
  or the database password — both bypass RLS entirely.
- **Google sign-in** needs two OAuth clients, and the app uses the *web* one's id. Full steps,
  and the traps, are in [`supabase/README.md`](supabase/README.md).
- **Maps/Places key:** [Google Cloud Console](https://console.cloud.google.com/) — enable the
  Maps SDK for Android and the Places API.
- **Spotify credentials are not needed here.** They live on the proxy in [`server/`](server/),
  never in the app. See that folder's README to run your own.

The Maps and proxy keys are optional — without them you lose map tiles and venue search but the
app still runs. The three sign-in values are not: every screen sits behind a session gate, so a
build without them cannot get past the sign-in screen.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Project layout

```
app/src/main/java/dev/abhinav/artistpin/
├── core/            design system, auth, database (Room), DI modules, shared models
├── data/            repositories, backend client, third-party API clients
├── data/sync/       the outbox, the drain, and the WorkManager job that runs it
├── feature/         one package per screen (home/map, artist, event, event edit, stats, sign-in)
└── navigation/      nav graph and routes

supabase/            schema, RLS policies and functions — the database in version control
server/              Vercel proxy holding the Spotify credentials
```

Anything a chart claims is arithmetic, and arithmetic is where a screen can be wrong without
looking wrong — so the treemap layout, the chart sizing and the generated headlines are pure
functions in [`feature/stats`](app/src/main/java/dev/abhinav/artistpin/feature/stats), tested
without a screen.

`ConcertRepository` is the boundary every screen goes through, and it is an interface. That seam is
why moving from one phone's SQLite to a shared Postgres, and then to offline-first sync, changed no
screen at all — the ViewModels already depended on exactly this surface.

`USE_BACKEND` decides which implementation `dataModule` binds:

- `RoomConcertRepository` — device-only, no account, the original.
- `OfflineFirstConcertRepository` — Room for reads, an outbox for writes, drained by
  [`data/sync`](app/src/main/java/dev/abhinav/artistpin/data/sync). This is what a backend build
  runs.


## Docs

- [`docs/architecture-plan.html`](docs/architecture-plan.html) — what it would take to scale this
  from one phone's local database to a real multi-user backend, with diagrams.
- [`docs/execution-plan.md`](docs/execution-plan.md) — that plan broken into a priority-ordered,
  code-sized backlog, with what was actually built and what it cost.
- [`supabase/README.md`](supabase/README.md) — the catalog/library split, sign-in setup, and the
  behaviours that changed meaning once the catalog is shared.
