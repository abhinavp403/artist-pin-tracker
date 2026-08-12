/**
 * Liveness check that needs no credentials, so a deployment can be verified before — or without —
 * the Spotify variables being set. Deliberately unauthenticated: it reports whether configuration
 * exists, never what it is.
 */
export default function handler(req, res) {
  // Never cached. Reporting a stale "configured" after a variable changed would make this the
  // one endpoint you cannot trust, which defeats the point of having it.
  res.setHeader("Cache-Control", "no-store");
  res.status(200).json({
    ok: true,
    spotifyConfigured: Boolean(process.env.SPOTIFY_CLIENT_ID && process.env.SPOTIFY_CLIENT_SECRET),
    keyRequired: Boolean(process.env.ARTISTPIN_API_KEY),
  });
}
