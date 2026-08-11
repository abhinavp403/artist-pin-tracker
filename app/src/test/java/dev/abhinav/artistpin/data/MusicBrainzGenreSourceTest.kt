package dev.abhinav.artistpin.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MusicBrainzGenreSourceTest {

    private class FakeApi(
        /** Null stands for "the search found the artist you asked for", the ordinary case. */
        private val matches: List<MusicBrainzArtistRef>? = null,
        private val artist: MusicBrainzArtist = MusicBrainzArtist(),
        private val fail: Boolean = false,
    ) : MusicBrainzApi {
        var requests = 0
        val requestedMbids = mutableListOf<String>()

        override suspend fun searchArtist(query: String, format: String, limit: Int): MusicBrainzSearchResponse {
            requests++
            if (fail) throw IOException("offline")
            val results = matches
                ?: listOf(MusicBrainzArtistRef(id = "mbid", name = query.substringAfter('"').substringBefore('"')))
            return MusicBrainzSearchResponse(results)
        }

        override suspend fun artist(mbid: String, include: String, format: String): MusicBrainzArtist {
            requests++
            requestedMbids += mbid
            return artist
        }
    }

    private fun source(api: MusicBrainzApi, waits: MutableList<Long> = mutableListOf()) =
        MusicBrainzGenreSource(
            api = api,
            minRequestSpacingMillis = 1_000,
            now = { 0L },                       // clock never advances, so spacing always applies
            pause = { waits += it },
        )

    private fun tag(name: String, count: Int) = MusicBrainzTag(name, count)

    @Test
    fun `genres come back ordered by how many people applied them`() = runTest {
        val api = FakeApi(
            artist = MusicBrainzArtist(
                genres = listOf(tag("techno", 3), tag("minimal techno", 9), tag("tech house", 6)),
            ),
        )

        assertEquals(
            listOf("minimal techno", "tech house", "techno"),
            source(api).profileFor("Boris Brejcha").genres,
        )
    }

    /** Plenty of smaller acts have free-form tags but no curated genre yet. */
    @Test
    fun `tags stand in when an artist has no curated genres`() = runTest {
        val api = FakeApi(artist = MusicBrainzArtist(tags = listOf(tag("melodic house", 2))))

        assertEquals(listOf("melodic house"), source(api).profileFor("Colyn").genres)
    }

    @Test
    fun `no match yields no genres rather than an error`() = runTest {
        assertTrue(source(FakeApi(matches = emptyList())).profileFor("Nobody").genres.isEmpty())
    }

    /** Searching "Dixon" leads with Willie Dixon, which is how the DJ ended up filed under blues. */
    @Test
    fun `only an exact name match is followed up`() = runTest {
        val api = FakeApi(
            matches = listOf(
                MusicBrainzArtistRef(id = "willie", name = "Willie Dixon", score = 100),
                MusicBrainzArtistRef(id = "alesha", name = "Alesha Dixon", score = 92),
            ),
            artist = MusicBrainzArtist(genres = listOf(tag("chicago blues", 5))),
        )

        assertTrue(source(api).profileFor("Someone Else").genres.isEmpty())
        assertTrue("a near miss must not be looked up", api.requestedMbids.isEmpty())
    }

    @Test
    fun `a pinned artist skips the search entirely`() = runTest {
        val api = FakeApi(artist = MusicBrainzArtist(genres = listOf(tag("deep house", 4))))

        assertEquals(listOf("deep house"), source(api).profileFor("Bedouin").genres)
        assertEquals(listOf("c9b34ac9-83ab-44f4-8358-348d8ea8d6c2"), api.requestedMbids)
        assertEquals("the search should be skipped", 1, api.requests)
    }

    @Test
    fun `a network failure degrades to empty`() = runTest {
        assertTrue(source(FakeApi(fail = true)).profileFor("Boris Brejcha").genres.isEmpty())
    }

    /** MusicBrainz blocks clients that exceed one request per second. */
    @Test
    fun `requests are spaced out to respect the rate limit`() = runTest {
        val waits = mutableListOf<Long>()
        val api = FakeApi(artist = MusicBrainzArtist(genres = listOf(tag("house", 1))))

        source(api, waits).profileFor("Black Coffee")

        assertEquals(2, api.requests)                       // search + lookup
        assertTrue("every request should be spaced", waits.size >= 1)
        assertTrue("spacing should be at least a second", waits.all { it >= 1_000 })
    }

    /** This source only knows genres; it must not claim a portrait it never fetched. */
    @Test
    fun `it never reports a portrait or profile link`() = runTest {
        val profile = source(FakeApi(artist = MusicBrainzArtist(genres = listOf(tag("techno", 1)))))
            .profileFor("Boris Brejcha")

        assertEquals(null, profile.imageUrl)
        assertEquals(null, profile.spotifyUrl)
    }
}
