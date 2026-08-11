package dev.abhinav.artistpin.data

import java.text.Normalizer
import java.util.Locale

/**
 * Reduces an artist name to a form two spellings of the same act share: lower case, no accents,
 * no punctuation or spacing. "Lane 8" and "Lane8" collapse together, "Dixon" and "Dixon Dallas"
 * do not.
 */
fun String.normalizedArtistKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(DIACRITICS, "")
    .lowercase(Locale.ROOT)
    .filter { it.isLetterOrDigit() }

private val DIACRITICS = Regex("\\p{Mn}+")

/**
 * A hand-pinned identity for an artist whose name is too common for a search to resolve.
 *
 * Search ranks by general popularity, which is the wrong signal for a library of club acts: a
 * search for "Dixon" puts the country singer Dixon Dallas first, and MusicBrainz answers with
 * Willie Dixon. Name matching alone can't split the tie either, since several distinct artists
 * are called exactly "Bedouin". These IDs were each verified against the artist's release list.
 */
data class ArtistOverride(
    val spotifyId: String? = null,
    val musicBrainzId: String? = null,
)

private val OVERRIDES: Map<String, ArtistOverride> = mapOf(
    // Steffen Berkhahn — Innervisions. Not Dixon Dallas, and not Willie Dixon.
    "dixon" to ArtistOverride(
        spotifyId = "3wc57nV2fGEoM8x4xPK1O9",
        musicBrainzId = "f912d0c6-52c6-4b0b-8aaa-975a9afdfee3",
    ),
    // The Brooklyn duo, not the UK space rock band of the same name.
    "bedouin" to ArtistOverride(
        spotifyId = "5bKdC6382t97Qnpvs81Rqx",
        musicBrainzId = "c9b34ac9-83ab-44f4-8358-348d8ea8d6c2",
    ),
)

fun overrideFor(artistName: String): ArtistOverride? = OVERRIDES[artistName.normalizedArtistKey()]
