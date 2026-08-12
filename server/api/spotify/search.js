import { fail, proxy, rejectedForKey } from "../_lib/spotify.js";

/**
 * `GET /api/spotify/search?q=bedouin&limit=10`
 *
 * Artists only, and never more than ten — the app has no use for the rest of Spotify's search,
 * and a narrower surface is a smaller thing to abuse. Spotify itself rejects a limit above 10
 * on this endpoint.
 */
export default async function handler(req, res) {
  if (rejectedForKey(req, res)) return;

  const query = (req.query.q ?? "").toString().trim();
  if (!query) {
    res.status(400).json({ error: "q is required" });
    return;
  }

  const requested = Number.parseInt(req.query.limit, 10);
  const limit = Number.isFinite(requested) ? Math.min(Math.max(requested, 1), 10) : 10;

  try {
    await proxy(res, "/search", { q: query, type: "artist", limit });
  } catch (error) {
    fail(res, error);
  }
}
