# ArtistPin proxy

A three-file serverless function whose only job is to hold the Spotify credentials that used to
be compiled into the Android APK.

The app calls this; this calls Spotify. Responses are passed through **unchanged**, so the app's
existing Kotlin models parse them exactly as before.

| Route | Forwards to |
| --- | --- |
| `GET /api/spotify/search?q=…&limit=…` | `GET /v1/search?type=artist` |
| `GET /api/spotify/artist/{id}` | `GET /v1/artists/{id}` |

## Environment variables

Set these on the deployment — **never** in a file in this repo.

| Variable | Required | Purpose |
| --- | --- | --- |
| `SPOTIFY_CLIENT_ID` | yes | Client-credentials app id from the Spotify dashboard |
| `SPOTIFY_CLIENT_SECRET` | yes | Its secret. This is the value that must never ship in the APK again |
| `ARTISTPIN_API_KEY` | no | If set, requests must carry a matching `X-ArtistPin-Key` header |

Until the two Spotify variables are set the endpoints answer `503` with a message saying so,
rather than failing in some confusing way.

### About `ARTISTPIN_API_KEY`

It ships inside the APK, so it is not a real secret. It is worth setting anyway: unlike the
Spotify credential it is useless anywhere except this proxy, and rotating it is a change here
rather than a change in the Spotify dashboard. Without it, anyone who finds the URL can spend
your Spotify rate limit.

## Local development

```bash
npx vercel dev
```

## Why a proxy at all

Three reasons, in order of how much they matter:

1. **The secret stops shipping to users.** Anyone can decompile an APK.
2. **One lookup serves everyone.** Responses are edge-cached for a day; artist artwork does not
   change. Ten thousand phones asking about the same DJ becomes one request.
3. **It is the first piece of the backend** described in `../docs/architecture-plan.html` — the
   same place a real API would eventually live.
