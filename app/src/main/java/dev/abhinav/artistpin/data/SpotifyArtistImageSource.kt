package dev.abhinav.artistpin.data

import android.util.Log
import dev.abhinav.artistpin.core.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class SpotifySearchResponse(
    val artists: SpotifyArtistPage = SpotifyArtistPage(),
)

@Serializable
data class SpotifyArtistPage(
    val items: List<SpotifyArtist> = emptyList(),
)

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = "",
    val genres: List<String> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("external_urls") val externalUrls: Map<String, String> = emptyMap(),
)

@Serializable
data class SpotifyImage(
    val url: String = "",
    val width: Int? = null,
)

/**
 * Spotify, reached through this app's own proxy rather than directly.
 *
 * The proxy holds the client credentials that used to be compiled into the APK and hands back
 * Spotify's responses untouched — which is why every model above is unchanged. No Authorization
 * header here: the app has nothing left to authenticate with, which is the entire point.
 */
interface SpotifyApi {
    @GET("spotify/search")
    suspend fun searchArtist(
        @Query("q") query: String,
        // Enough results for the right artist to be in there even when better-known acts with
        // similar names outrank them. Spotify rejects anything above 10 on this endpoint.
        @Query("limit") limit: Int = 10,
    ): SpotifySearchResponse

    @GET("spotify/artist/{id}")
    suspend fun artist(@Path("id") id: String): SpotifyArtist
}

class SpotifyArtistImageSource(
    private val api: SpotifyApi,
) : ArtistImageSource {

    override suspend fun profileFor(artistName: String): ArtistProfile = try {
        search(artistName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Spotify lookup failed for '$artistName'", e)
        ArtistProfile(lookupFailed = true)
    }

    private suspend fun search(artistName: String): ArtistProfile {
        val pinned = overrideFor(artistName)?.spotifyId
        val artist = if (pinned != null) {
            api.artist(id = pinned)
        } else {
            // Never the top hit by default. Search ranks by overall popularity, so "Dixon"
            // returns Dixon Dallas first — an exact name match is the only signal here worth
            // trusting, and no match at all beats confidently showing the wrong face.
            api.searchArtist(query = artistName)
                .artists.items
                .firstOrNull { it.name.normalizedArtistKey() == artistName.normalizedArtistKey() }
        }
        if (artist == null) {
            Log.d(TAG, "No exact Spotify match for '$artistName'")
            return ArtistProfile()
        }
        // Spotify returns images largest-first; ~500px is plenty for a map pin.
        val image = artist.images
            .sortedByDescending { it.width ?: 0 }
            .firstOrNull { (it.width ?: 0) <= MAX_USEFUL_WIDTH }
            ?.url
            ?: artist.images.lastOrNull()?.url
        return ArtistProfile(
            imageUrl = image,
            genres = artist.genres.filter { it.isNotBlank() },
            spotifyUrl = artist.externalUrls["spotify"],
        )
    }

    private companion object {
        const val TAG = "ArtistImages"
        const val MAX_USEFUL_WIDTH = 640
    }
}

/**
 * Merges field by field rather than taking the first source that answers: Spotify is the only one
 * with genres, but Deezer sometimes has a portrait where Spotify does not, so a per-field merge
 * gets more than either alone.
 *
 * Only stops once every field is filled: genres come from a source further down the chain than
 * the portrait does, so stopping at the first portrait would skip them entirely.
 */
class ChainedArtistImageSource(
    private val sources: List<ArtistImageSource>,
) : ArtistImageSource {
    override suspend fun profileFor(artistName: String): ArtistProfile {
        var merged = ArtistProfile()
        for (source in sources) {
            val next = source.profileFor(artistName)
            merged = ArtistProfile(
                imageUrl = merged.imageUrl ?: next.imageUrl,
                genres = merged.genres.ifEmpty { next.genres },
                spotifyUrl = merged.spotifyUrl ?: next.spotifyUrl,
                // Any source erroring means the answer is incomplete, so it stays retryable.
                lookupFailed = merged.lookupFailed || next.lookupFailed,
            )
            if (merged.imageUrl != null && merged.genres.isNotEmpty() && merged.spotifyUrl != null) break
        }
        return merged
    }
}
