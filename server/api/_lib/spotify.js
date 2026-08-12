/**
 * Spotify credentials live here and nowhere else.
 *
 * The Android app used to hold the client id and secret in its own BuildConfig, which meant
 * anyone who decompiled the APK had them. This module is the only thing that ever sees them; the
 * app just asks this proxy for artists and gets Spotify's own JSON back, unchanged.
 */

const TOKEN_URL = "https://accounts.spotify.com/api/token";
const API_BASE = "https://api.spotify.com/v1";

/**
 * Cached across warm invocations of the same instance — a serverless function is reused between
 * requests, so this behaves like the app's old in-memory token cache, minus the app.
 */
let cachedToken = null;
let expiresAt = 0;

/** Renewed a minute early so a request never races the expiry. */
const RENEW_MARGIN_MS = 60_000;

async function fetchToken() {
  const id = process.env.SPOTIFY_CLIENT_ID;
  const secret = process.env.SPOTIFY_CLIENT_SECRET;
  if (!id || !secret) {
    throw new ConfigError(
      "SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET are not set on this deployment",
    );
  }

  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: {
      Authorization: `Basic ${Buffer.from(`${id}:${secret}`).toString("base64")}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: "grant_type=client_credentials",
  });

  if (!response.ok) {
    throw new Error(`Spotify token request failed: ${response.status}`);
  }

  const body = await response.json();
  cachedToken = body.access_token;
  expiresAt = Date.now() + body.expires_in * 1000 - RENEW_MARGIN_MS;
  return cachedToken;
}

async function token({ forceRefresh = false } = {}) {
  if (!forceRefresh && cachedToken && Date.now() < expiresAt) return cachedToken;
  return fetchToken();
}

export class ConfigError extends Error {}

/**
 * Forwards to Spotify and hands the response back verbatim.
 *
 * Passing the body through untouched is the point: the app's existing Kotlin models parse
 * Spotify's shapes already, so swapping the base URL is the whole client-side change.
 */
export async function proxy(res, path, searchParams) {
  const url = new URL(`${API_BASE}${path}`);
  for (const [key, value] of Object.entries(searchParams ?? {})) {
    if (value !== undefined && value !== null) url.searchParams.set(key, value);
  }

  let upstream = await fetch(url, {
    headers: { Authorization: `Bearer ${await token()}` },
  });

  // A cached token can be revoked server-side; one 401 earns exactly one retry.
  if (upstream.status === 401) {
    upstream = await fetch(url, {
      headers: { Authorization: `Bearer ${await token({ forceRefresh: true })}` },
    });
  }

  const body = await upstream.text();
  res.status(upstream.status);
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  // Deliberately uncached at the edge. A shared CDN cache is served *before* the function runs,
  // so a cached response skips rejectedForKey entirely — once one authorised request warmed the
  // cache, anyone could replay that query with no key at all. Caching has to move behind the
  // check (a keyed store the handler reads after authorising) rather than in front of it.
  res.setHeader("Cache-Control", "no-store");
  res.send(body);
}

/**
 * Optional shared key, so the endpoint isn't a free Spotify quota for anyone who finds the URL.
 *
 * This key still ships inside the APK, so it is not a real secret — but unlike the Spotify
 * credential it is worthless anywhere else and can be rotated here without touching the Spotify
 * dashboard. Leave ARTISTPIN_API_KEY unset and the proxy stays open.
 */
export function rejectedForKey(req, res) {
  const expected = process.env.ARTISTPIN_API_KEY;
  if (!expected) return false;
  if (req.headers["x-artistpin-key"] === expected) return false;

  res.status(401).json({ error: "unauthorized" });
  return true;
}

/** Turns a thrown error into a response the app can report without crashing. */
export function fail(res, error) {
  const status = error instanceof ConfigError ? 503 : 502;
  console.error(error);
  res.status(status).json({ error: error.message ?? "upstream request failed" });
}
