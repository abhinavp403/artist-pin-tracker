package dev.abhinav.artistpin.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CachingArtistSearchTest {

    private class RecordingSearch(var results: List<ArtistSuggestion> = listOf(ArtistSuggestion("Bedouin"))) :
        RemoteArtistSearch {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): List<ArtistSuggestion> {
            queries += query
            return results
        }
    }

    @Test
    fun `a repeated query is answered from memory`() = runTest {
        val delegate = RecordingSearch()
        val search = CachingArtistSearch(delegate)

        val first = search.search("bedouin")
        val second = search.search("bedouin")

        assertEquals(first, second)
        assertEquals(listOf("bedouin"), delegate.queries)
    }

    /** Backspacing and retyping a character is the same query, whatever the casing or spacing. */
    @Test
    fun `queries match regardless of case and surrounding space`() = runTest {
        val delegate = RecordingSearch()
        val search = CachingArtistSearch(delegate)

        search.search("Bedouin")
        search.search(" bedouin ")

        assertEquals(1, delegate.queries.size)
    }

    @Test
    fun `different queries each reach the provider`() = runTest {
        val delegate = RecordingSearch()
        val search = CachingArtistSearch(delegate)

        search.search("bedouin")
        search.search("dixon")

        assertEquals(listOf("bedouin", "dixon"), delegate.queries)
    }

    /**
     * An empty answer might be a failed lookup rather than a real "nobody by that name", and
     * caching that would make one moment offline permanent for the rest of the session.
     */
    @Test
    fun `an empty answer is not cached`() = runTest {
        val delegate = RecordingSearch(results = emptyList())
        val search = CachingArtistSearch(delegate)

        search.search("colyn")
        delegate.results = listOf(ArtistSuggestion("Colyn"))
        val retry = search.search("colyn")

        assertEquals(listOf("Colyn"), retry.map { it.name })
        assertEquals(2, delegate.queries.size)
    }

    @Test
    fun `the cache stays bounded, dropping the least recently used query`() = runTest {
        val delegate = RecordingSearch()
        val search = CachingArtistSearch(delegate, maxEntries = 2)

        search.search("a")
        search.search("b")
        search.search("a")   // keeps "a" fresh, so "b" is now the stale one
        search.search("c")   // evicts "b"

        search.search("a")
        assertEquals("'a' is still cached", 3, delegate.queries.size)

        search.search("b")
        assertEquals("'b' was evicted and had to be fetched again", 4, delegate.queries.size)
    }
}
