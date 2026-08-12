package dev.abhinav.artistpin.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One row in the artist typeahead. [timesSeen] is 0 for an artist not in your library yet. */
data class ArtistSuggestion(
    val name: String,
    val imageUrl: String? = null,
    val genres: List<String> = emptyList(),
    val timesSeen: Int = 0,
) {
    val isInLibrary: Boolean get() = timesSeen > 0
}

/**
 * Artists the user has never seen, looked up by name as they type.
 *
 * Separate from [ArtistImageSource], which answers "who is this exact artist" for artwork. This
 * one answers "who might you mean", so partial names have to come back rather than be rejected.
 */
interface RemoteArtistSearch {
    /** Never throws: an unreachable provider just means the local library is all you get. */
    suspend fun search(query: String): List<ArtistSuggestion>
}

/**
 * Remembers what recent queries answered, so backspacing a character and typing it again — or
 * coming back to the same artist a minute later — doesn't cost another round trip.
 *
 * Small and in-memory on purpose: the point is a burst of typing around the same few names, not
 * a durable index. Results outlive nothing beyond the process.
 */
class CachingArtistSearch(
    private val delegate: RemoteArtistSearch,
    private val maxEntries: Int = MAX_CACHED_QUERIES,
) : RemoteArtistSearch {

    private val mutex = Mutex()

    /** Access-ordered, so eviction drops the query you're least likely to type next. */
    private val cache = object : LinkedHashMap<String, List<ArtistSuggestion>>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ArtistSuggestion>>) =
            size > maxEntries
    }

    override suspend fun search(query: String): List<ArtistSuggestion> {
        val key = query.trim().lowercase()
        mutex.withLock { cache[key] }?.let { return it }

        val results = delegate.search(query)
        // An empty answer is either "nobody by that name" or a lookup that failed — the two are
        // indistinguishable here, and caching the second would make one bad moment permanent.
        if (results.isNotEmpty()) {
            mutex.withLock { cache[key] = results }
        }
        return results
    }

    private companion object {
        const val MAX_CACHED_QUERIES = 32
    }
}

class SpotifyArtistSearch(
    private val api: SpotifyApi,
) : RemoteArtistSearch {

    override suspend fun search(query: String): List<ArtistSuggestion> = try {
        api.searchArtist(query = query).artists.items
            .filter { it.name.isNotBlank() }
            .map { artist ->
                ArtistSuggestion(
                    name = artist.name,
                    // Search returns images largest-first; the smallest is plenty for a 36dp row.
                    imageUrl = artist.images.minByOrNull { it.width ?: Int.MAX_VALUE }?.url,
                    genres = artist.genres,
                )
            }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A dead search field is worse than a short one: fall back to what's already in the library.
        Log.d(TAG, "Artist search failed for '$query'", e)
        emptyList()
    }

    private companion object {
        const val TAG = "ArtistImages"
    }
}
