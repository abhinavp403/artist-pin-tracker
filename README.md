# ArtistPin

A personal Android app for tracking concerts on a map: cities and venues you've been to, the
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
- **Backup and restore** to a JSON file, since the data lives only on the device (see
  [`docs/architecture-plan.html`](docs/architecture-plan.html) for what changes if that stops
  being true).

## Stack

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **DI:** Koin
- **Persistence:** Room (SQLite), Jetpack DataStore for preferences
- **Networking:** Retrofit + OkHttp + kotlinx.serialization
- **Maps & places:** Google Maps Compose, Places SDK
- **Artist metadata:** Spotify Web API (images, search), MusicBrainz (genres — Spotify dropped
  this from its public API), Deezer (portrait fallback)
- **Media:** Coil 3, Media3 (video playback)
- **Tests:** JUnit, Robolectric, Turbine, Koin's compile-time graph verification

## Building it

Create `local.properties` in the project root (already gitignored) with:

```properties
MAPS_API_KEY=your-google-maps-and-places-key

# Optional — both have working defaults
API_BASE_URL=https://your-own-proxy.vercel.app/api/
ARTISTPIN_API_KEY=matches-ARTISTPIN_API_KEY-on-the-proxy
```

- **Maps/Places key:** [Google Cloud Console](https://console.cloud.google.com/) — enable the
  Maps SDK for Android and the Places API.
- **Spotify credentials are not needed here.** They live on the proxy in [`server/`](server/),
  never in the app. See that folder's README to run your own.

The app runs without any of these set — you just won't get map tiles or venue search.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Project layout

```
app/src/main/java/dev/abhinav/artistpin/
├── core/            design system, database (Room), DI modules, shared models
├── data/            repositories and third-party API clients
├── feature/         one package per screen (home/map, artist, event, event edit)
└── navigation/       nav graph and routes
```

`ConcertRepository` is the boundary every screen goes through — no screen talks to Room directly.
That seam is what makes [`docs/execution-plan.md`](docs/execution-plan.md)'s backend migration a
swap-the-implementation change rather than a rewrite.

## Docs

- [`docs/architecture-plan.html`](docs/architecture-plan.html) — what it would take to scale this
  from one phone's local database to a real multi-user backend, with diagrams.
- [`docs/execution-plan.md`](docs/execution-plan.md) — that plan broken into a priority-ordered,
  code-sized backlog.
