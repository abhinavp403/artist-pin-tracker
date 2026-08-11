package dev.abhinav.artistpin.data

import android.util.Log
import dev.abhinav.artistpin.core.model.ArtistProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.Base64

@Serializable
data class SpotifyTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long = 3600,
)

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

interface SpotifyAuthApi {
    @FormUrlEncoded
    @POST("api/token")
    suspend fun token(
        @Header("Authorization") basicAuth: String,
        @Field("grant_type") grantType: String = "client_credentials",
    ): SpotifyTokenResponse
}

interface SpotifyApi {
    @GET("v1/search")
    suspend fun searchArtist(
        @Header("Authorization") bearer: String,
        @Query("q") query: String,
        @Query("type") type: String = "artist",
        // Enough results for the right artist to be in there even when better-known acts with
        // similar names outrank them. Spotify rejects anything above 10 on this endpoint.
        @Query("limit") limit: Int = 10,
    ): SpotifySearchResponse

    @GET("v1/artists/{id}")
    suspend fun artist(
        @Header("Authorization") bearer: String,
        @Path("id") id: String,
    ): SpotifyArtist
}

/**
 * Client-credentials token holder. The token is app-wide rather than per-user, cached until just
 * before it expires, and guarded by a mutex so a burst of artist lookups triggers one fetch.
 */
class SpotifyTokenProvider(
    private val authApi: SpotifyAuthApi,
    private val clientId: String,
    private val clientSecret: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var expiresAtMillis: Long = 0

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    suspend fun bearer(forceRefresh: Boolean = false): String? {
        if (!isConfigured) return null
        return mutex.withLock {
            val cached = cachedToken
            if (!forceRefresh && cached != null && now() < expiresAtMillis) {
                return@withLock "Bearer $cached"
            }
            val basic = Base64.getEncoder()
                .encodeToString("$clientId:$clientSecret".toByteArray())
            val response = authApi.token(basicAuth = "Basic $basic")
            cachedToken = response.accessToken
            // Renew a minute early so a lookup never races the expiry.
            expiresAtMillis = now() + (response.expiresInSeconds - 60).coerceAtLeast(0) * 1_000
            "Bearer ${response.accessToken}"
        }
    }
}

class SpotifyArtistImageSource(
    private val api: SpotifyApi,
    private val tokenProvider: SpotifyTokenProvider,
) : ArtistImageSource {

    override suspend fun profileFor(artistName: String): ArtistProfile = try {
        val bearer = tokenProvider.bearer()
        if (bearer == null) {
            ArtistProfile()
        } else {
            val profile = search(artistName, bearer)
            if (profile.isEmpty) retryWithFreshToken(artistName) else profile
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Spotify lookup failed for '$artistName'", e)
        ArtistProfile(lookupFailed = true)
    }

    /** A cached token can be revoked server-side, so one 401 earns exactly one retry. */
    private suspend fun retryWithFreshToken(artistName: String): ArtistProfile =
        tokenProvider.bearer(forceRefresh = true)?.let { search(artistName, it) } ?: ArtistProfile()

    private suspend fun search(artistName: String, bearer: String): ArtistProfile {
        val pinned = overrideFor(artistName)?.spotifyId
        val artist = if (pinned != null) {
            api.artist(bearer = bearer, id = pinned)
        } else {
            // Never the top hit by default. Search ranks by overall popularity, so "Dixon"
            // returns Dixon Dallas first — an exact name match is the only signal here worth
            // trusting, and no match at all beats confidently showing the wrong face.
            api.searchArtist(bearer = bearer, query = artistName)
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
