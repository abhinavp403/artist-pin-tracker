# Supabase backend

The database behind [`../docs/execution-plan.md`](../docs/execution-plan.md)'s Milestone B. Nothing
in the Android app talks to this yet — B5 and B6 are what connect it. Until then the app runs
entirely on Room exactly as before, which is the point: this milestone is mergeable on its own.

## The one idea

Every table is either **catalog** or **library**.

| | tables | owned by | client can |
|---|---|---|---|
| **catalog** | `cities`, `venues`, `artists` | nobody — shared | read only |
| **library** | `events`, `event_artists`, `event_media` | one user | read/write their own |

Two people who saw the same show at Knockdown Center share one venue row and one artist row, but
have completely separate event rows. That split is what makes the app multi-tenant, and it is what
the Row-Level Security policies attach to.

Clients never write the catalog directly. They call the `catalog_find_or_create_*` functions, which
hold that privilege and use it for one narrow thing: return the id of the row for this name,
inserting it only if nobody has yet.

## Files

Run in order — they depend on each other.

| file | what it is |
|---|---|
| `migrations/0001_schema.sql` | tables, natural-key indexes, constraints |
| `migrations/0002_policies.sql` | RLS policies and grants (plan item B4) |
| `migrations/0003_catalog_functions.sql` | find-or-create for city/venue/artist (B3) |
| `migrations/0004_library_functions.sql` | `save_event`, delete, rename (B3) |
| `migrations/0005_views.sql` | the aggregate reads the map and artist tab need |
| `migrations/0006_view_grants.sql` | revokes anon from the views (0002 ran before they existed) |
| `migrations/0007_import.sql` | one-time import of a device's local library (B7) |
| `migrations/0008_export.sql` | the mirror — the caller's library in the app's backup format |
| `verify.sql` | read-only audit of the security properties — run after any migration |

## Setting it up

1. **Create a project** at [supabase.com](https://supabase.com). Pick the region closest to you —
   it is where every query round-trips to. Note the database password somewhere safe; it is shown
   once.
2. **Run the migrations.** Either paste each file into the SQL Editor in the order above, or with
   the CLI:
   ```bash
   supabase link --project-ref <your-project-ref>
   supabase db push
   ```
3. **Enable Google sign-in** — Authentication → Providers → Google. This is B2 and needs an OAuth
   client from the Google Cloud console; the same project already holding your Maps key is fine.
4. **Collect the two client values** from Settings → API:
   - the **project URL**
   - the **publishable / anon key**

   Both go in `local.properties` when B5 lands. Neither is a secret in the sense the Spotify
   credential was — the anon key is designed to ship in the client, and RLS is what makes that
   safe. The **service role key is the opposite**: it bypasses RLS entirely and must never touch
   the app, this repo, or the Vercel proxy.

## Google sign-in (B2)

Three client values end up in `local.properties`:

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable / anon key>
GOOGLE_WEB_CLIENT_ID=<the WEB client id, see below>
```

The app uses Credential Manager with a Google id token, which needs **two** OAuth clients in the
Google Cloud console — the same project already holding your Maps key is fine.

1. **Android client** — *APIs & Services → Credentials → Create OAuth client ID → Android*.
   Package `dev.abhinav.artistpin`, plus the signing certificate's SHA-1 (the debug one is already
   recorded in `docs/execution-plan.md` under A4). Without this, Google refuses to mint a token for
   the app at all. **You never reference its client id anywhere in the code.**
2. **Web client** — *Create OAuth client ID → Web application*. This one's id is what goes in
   `GOOGLE_WEB_CLIENT_ID`, and its id **and secret** go into Supabase under
   *Authentication → Providers → Google*.
3. In that same Supabase provider screen, add the **web client id** to *Authorized Client IDs*.

The trap worth stating plainly, because the error it produces is unhelpful: **the app sends the
*web* client id as its `serverClientId`, not the Android one.** It reads backwards, but it is
correct — that field names the audience the token is minted *for* (your backend, i.e. Supabase),
not the app requesting it. Using the Android client id yields a token Supabase rejects with an
audience mismatch, which surfaces as a generic sign-in failure.

A second trap, handled in `GoogleCredentialClient` but worth knowing if you ever touch it: Google
is given the **SHA-256 hash** of the nonce, and Supabase is given the **raw** value. Supabase
hashes it and compares against the claim inside the token. Sending the same form to both produces
an "invalid nonce" that looks like a server fault.

Once B7 lands you will want a second test account anyway — that is the point at which the RLS
isolation becomes testable end to end.

## Moving an existing library in

`import_backup(jsonb)` takes the app's own backup JSON — the exact shape the overflow menu already
writes to a file — and replays it. Trigger it from the app: **overflow → "Copy my shows to my
account"**, while `USE_BACKEND` is still `false`. The full cutover order is in
[`../docs/execution-plan.md`](../docs/execution-plan.md); the short version is *import first, flip
the flag second*, because the local library has to be readable in order to be uploaded.

**Safe to run twice.** Events keep their original ids, which Room already generates as UUIDs, so a
second run finds them present and skips. Cities, venues and artists go through find-or-create
instead — the shared catalog may already hold "Bedouin" under a different id, which is the point of
it being shared.

Nothing local is deleted. Setting `USE_BACKEND` back to `false` returns to the Room library exactly
as it was, which is the rollback.

## Things that changed meaning in the port

Three behaviours could not be carried over literally, because they were only correct when you were
the only user. They are called out in comments at the point they occur; collected here because
they are the substantive differences between the Room schema and this one.

**Orphan pruning is gone.** `deleteOrphanArtists`, `deleteOrphanVenues` and `deleteOrphanCities`
cleaned up rows nothing referenced any more. On a shared catalog that is destructive: deleting your
only Knockdown Center show would delete the venue and cascade into every other user's shows there.
It is also unanswerable from the client — RLS means a user sees only their own events, so *every*
venue looks orphaned to them. Unreferenced catalog rows now just persist. They are invisible (every
query joins through `events`), tiny, and ready for the next user who types that name.

**Renaming an artist no longer renames the artist.** `renameArtist` ran `UPDATE artists SET name`,
which on a shared catalog would rename them on every other user's map too. It now find-or-creates
the correct name and repoints *your* `event_artists` rows at it. Note that this is exactly what the
local version already did whenever the new name collided with an existing artist — the collision
branch turns out to have been the general case. Same for `deleteArtist`: it removes your links and
any shows that are left with nobody on the bill, and leaves the catalog row alone.

**Venue coordinates are set once.** The first person to add a venue fixes where its pin sits.
Later callers' Places results are discarded rather than allowed to nudge the marker on everyone
else's map.

## Two ways to get this badly wrong

Both are silent — they return data and look like success.

**A view without `security_invoker = on`** runs as its owner, not its caller, so RLS never applies
and every user sees every user's shows. Every view in `0005` sets it. This is the same shape as the
CDN cache that sat in front of the Spotify proxy's API-key check in Milestone A: a layer that
answers before the authorisation runs.

**A SECURITY DEFINER function without a pinned `search_path`** lets the caller decide what code
runs with the owner's rights, by putting a same-named table in a schema they control. Every
function in `0003` sets `search_path = public, pg_temp`, with `pg_temp` last so a temp table cannot
shadow a real one.

## Verification

`verify.sql` is read-only and asserts the properties that fail *silently*: RLS actually enabled,
catalog write policies absent, `WITH CHECK` on every insert/update policy, `security_invoker` on
every view, `search_path` pinned on every definer function, and `anon` reaching nothing. Every row
should read PASS. Re-run it after any migration that adds a table or a view.

It has earned its keep once already. All six migrations reported success and every structural
property was correct, but check 7 caught five views still holding Supabase's default `anon` grants
— `0002`'s revoke had run three files before those views existed. Not a disclosure (the views are
`security_invoker`, and the policies are scoped `to authenticated`, so anon was blocked twice
over), but a grant doing no work except waiting for one of those layers to slip. `0006` fixes it
and sets a default-privileges rule so the next object added does not repeat it.

The lesson is worth carrying: **a `revoke` acts only on what exists when it runs.** Ordering is
part of the security model, not just the syntax.

What remains untested is behavioural rather than structural — that two signed-in users genuinely
cannot see each other's rows. That needs two real accounts, so the moment to check it is right
after B2 lands sign-in.

Requires Postgres 15+ for `security_invoker` views, which every current Supabase project exceeds.
