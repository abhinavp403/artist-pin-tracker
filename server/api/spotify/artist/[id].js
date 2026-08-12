import { fail, proxy, rejectedForKey } from "../../_lib/spotify.js";

/**
 * `GET /api/spotify/artist/3wc57nV2fGEoM8x4xPK1O9`
 *
 * Used for the artists pinned by id in the app's override list, where a name search resolves to
 * the wrong person (Dixon, Bedouin).
 */
export default async function handler(req, res) {
  if (rejectedForKey(req, res)) return;

  const id = (req.query.id ?? "").toString();
  // Spotify ids are base62; anything else is a typo or someone poking at the path.
  if (!/^[A-Za-z0-9]{22}$/.test(id)) {
    res.status(400).json({ error: "not a Spotify artist id" });
    return;
  }

  try {
    await proxy(res, `/artists/${id}`);
  } catch (error) {
    fail(res, error);
  }
}
