package dev.abhinav.artistpin.data

import android.util.Log
import dev.abhinav.artistpin.core.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Resolves an artist name to whatever a provider knows about them. Behind an interface so the
 * provider can be swapped (Deezer needs no key; Spotify has better coverage and is the only one
 * with genres) without touching the repository.
 */
interface ArtistImageSource {
    suspend fun profileFor(artistName: String): ArtistProfile
}

@Serializable
data class DeezerSearchResponse(
    val data: List<DeezerArtist> = emptyList(),
)

@Serializable
data class DeezerArtist(
    val name: String = "",
    @SerialName("picture_big") val pictureBig: String? = null,
    @SerialName("picture_medium") val pictureMedium: String? = null,
)

interface DeezerApi {
    @GET("search/artist")
    suspend fun searchArtist(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10,
    ): DeezerSearchResponse
}

class DeezerArtistImageSource(private val api: DeezerApi) : ArtistImageSource {

    /**
     * Returns null rather than throwing on any failure — a missing portrait degrades the pin to
     * initials, which is never worth surfacing as an error.
     */
    /** Deezer has no genre or Spotify link on an artist, so it only ever fills in the portrait. */
    override suspend fun profileFor(artistName: String): ArtistProfile = try {
        // Same rule as the Spotify source: a name that isn't an exact match is a different
        // artist, and a wrong portrait is worse than initials.
        val artist = api.searchArtist(artistName).data
            .firstOrNull { it.name.normalizedArtistKey() == artistName.normalizedArtistKey() }
        if (artist == null) {
            Log.d(TAG, "No exact Deezer match for '$artistName'")
            ArtistProfile()
        } else {
            val url = artist.pictureBig ?: artist.pictureMedium
            ArtistProfile(imageUrl = url?.takeUnless { it.isBlank() || EMPTY_IMAGE_HASH in it })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Artist image lookup failed for '$artistName'", e)
        ArtistProfile(lookupFailed = true)
    }

    private companion object {
        const val TAG = "ArtistImages"

        /**
         * Artists with no photo still return a well-formed URL, but with the MD5 of the empty
         * string as the image hash. Verified against the live API: Playboi Carti returns this
         * while The Weeknd returns a real hash. Without this check the pin renders a blank
         * silhouette instead of falling back to initials.
         */
        const val EMPTY_IMAGE_HASH = "d41d8cd98f00b204e9800998ecf8427e"
    }
}
