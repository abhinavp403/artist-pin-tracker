package dev.abhinav.artistpin.data

import android.util.Log
import dev.abhinav.artistpin.core.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class MusicBrainzSearchResponse(
    val artists: List<MusicBrainzArtistRef> = emptyList(),
)

@Serializable
data class MusicBrainzArtistRef(
    val id: String = "",
    val name: String = "",
    val score: Int = 0,
)

@Serializable
data class MusicBrainzArtist(
    val name: String = "",
    val genres: List<MusicBrainzTag> = emptyList(),
    val tags: List<MusicBrainzTag> = emptyList(),
)

@Serializable
data class MusicBrainzTag(
    val name: String = "",
    val count: Int = 0,
)

interface MusicBrainzApi {
    @GET("ws/2/artist")
    suspend fun searchArtist(
        @Query("query") query: String,
        @Query("fmt") format: String = "json",
        // The exact-name match is often well down the list: a search for "Dixon" leads with
        // Willie Dixon and Alesha Dixon before it reaches the DJ.
        @Query("limit") limit: Int = 25,
    ): MusicBrainzSearchResponse

    @GET("ws/2/artist/{mbid}")
    suspend fun artist(
        @Path("mbid") mbid: String,
        @Query("inc") include: String = "genres+tags",
        @Query("fmt") format: String = "json",
    ): MusicBrainzArtist
}

/**
 * Genres only. Spotify removed the `genres` field from its artist object, and Deezer never had
 * one, so MusicBrainz is the only free source left that actually carries them.
 *
 * MusicBrainz enforces one request per second per client and will start refusing otherwise, so
 * every call goes through a mutex that spaces requests out. That makes a full backfill slow but
 * it runs in the background and only ever needs to happen once per artist.
 */
class MusicBrainzGenreSource(
    private val api: MusicBrainzApi,
    private val minRequestSpacingMillis: Long = 1_100,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : ArtistImageSource {

    private val throttle = Mutex()
    private var lastRequestAt = 0L

    override suspend fun profileFor(artistName: String): ArtistProfile = try {
        // Results come back best-scoring first, so among the exact name matches the first is the
        // most likely one. Anything that isn't an exact match is a different artist, not a near
        // miss — taking the top hit regardless is what filed Dixon under Chicago blues.
        val mbid = overrideFor(artistName)?.musicBrainzId
            ?: throttled { api.searchArtist("artist:\"$artistName\"") }
                .artists
                .firstOrNull { it.name.normalizedArtistKey() == artistName.normalizedArtistKey() }
                ?.id
        if (mbid.isNullOrBlank()) {
            Log.d(TAG, "No exact MusicBrainz match for '$artistName'")
            ArtistProfile()
        } else {
            val artist = throttled { api.artist(mbid) }
            // `genres` is the curated list and `tags` the free-form one; tags fill the gap for
            // artists nobody has formally genre-tagged yet.
            val source = artist.genres.ifEmpty { artist.tags }
            ArtistProfile(
                genres = source
                    .filter { it.name.isNotBlank() }
                    .sortedByDescending { it.count }
                    .take(MAX_GENRES)
                    .map { it.name },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "MusicBrainz lookup failed for '$artistName'", e)
        ArtistProfile(lookupFailed = true)
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = throttle.withLock {
        val waitFor = minRequestSpacingMillis - (now() - lastRequestAt)
        if (waitFor > 0) pause(waitFor)
        try {
            block()
        } finally {
            lastRequestAt = now()
        }
    }

    private companion object {
        const val TAG = "ArtistImages"
        const val MAX_GENRES = 3
    }
}
