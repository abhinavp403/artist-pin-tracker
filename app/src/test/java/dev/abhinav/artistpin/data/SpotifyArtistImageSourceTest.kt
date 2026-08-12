package dev.abhinav.artistpin.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SpotifyArtistImageSourceTest {

    private class FakeApi(private val results: List<SpotifyArtist>) : SpotifyApi {
        constructor(artist: SpotifyArtist?) : this(listOfNotNull(artist))

        var calls = 0
        var fetchedIds = mutableListOf<String>()
        var byId: SpotifyArtist? = null

        override suspend fun searchArtist(query: String, limit: Int): SpotifySearchResponse {
            calls++
            return SpotifySearchResponse(SpotifyArtistPage(results))
        }

        override suspend fun artist(id: String): SpotifyArtist {
            fetchedIds += id
            return byId ?: SpotifyArtist(id = id, name = "pinned")
        }
    }

    private fun artist(
        vararg widths: Int,
        name: String = "Playboi Carti",
        genres: List<String> = emptyList(),
        spotifyUrl: String? = null,
    ) = SpotifyArtist(
        name = name,
        genres = genres,
        images = widths.map { SpotifyImage(url = "https://i.scdn.co/image/$it", width = it) },
        externalUrls = spotifyUrl?.let { mapOf("spotify" to it) }.orEmpty(),
    )

    @Test
    fun `genres and the profile link come back alongside the image`() = runTest {
        val profile = source(
            FakeApi(
                artist(
                    640,
                    name = "Boris Brejcha",
                    genres = listOf("melodic techno", "minimal techno"),
                    spotifyUrl = "https://open.spotify.com/artist/abc",
                ),
            ),
        ).profileFor("Boris Brejcha")

        assertEquals(listOf("melodic techno", "minimal techno"), profile.genres)
        assertEquals("https://open.spotify.com/artist/abc", profile.spotifyUrl)
    }

    /** Most smaller acts have no genres at all, which must read as empty rather than fail. */
    @Test
    fun `an artist with no genres yields an empty list`() = runTest {
        assertEquals(
            emptyList<String>(),
            source(FakeApi(artist(640, name = "Kinahau"))).profileFor("Kinahau").genres,
        )
    }

    private fun source(api: SpotifyApi) = SpotifyArtistImageSource(api)

    @Test
    fun `picks a pin-sized image rather than the largest available`() = runTest {
        val url = source(FakeApi(artist(1000, 640, 160))).profileFor("Playboi Carti").imageUrl

        assertEquals("https://i.scdn.co/image/640", url)
    }

    @Test
    fun `falls back to the smallest image when all are oversized`() = runTest {
        val url = source(FakeApi(artist(3000, 1000))).profileFor("Playboi Carti").imageUrl

        assertEquals("https://i.scdn.co/image/1000", url)
    }

    @Test
    fun `an artist with no images yields null so the chain can try Deezer`() = runTest {
        assertNull(source(FakeApi(artist())).profileFor("Playboi Carti").imageUrl)
    }




    /**
     * The shape of the reported bug: searching "Dixon" led with the country singer Dixon Dallas.
     * Uses a name the override map doesn't cover, so only the matching rule is under test.
     */
    @Test
    fun `an exact name match wins over a better-ranked near miss`() = runTest {
        val api = FakeApi(listOf(artist(640, name = "Bedouin Soundclash"), artist(320, name = "Hiya")))

        val url = source(api).profileFor("Hiya").imageUrl

        assertEquals("https://i.scdn.co/image/320", url)
    }

    @Test
    fun `a name that only differs by spacing still matches`() = runTest {
        val url = source(FakeApi(artist(640, name = "Lane 8"))).profileFor("Lane8").imageUrl

        assertEquals("https://i.scdn.co/image/640", url)
    }

    /** Initials are honest; another artist's face is not. */
    @Test
    fun `no exact match yields nothing rather than the top hit`() = runTest {
        val api = FakeApi(listOf(artist(640, name = "Skeptika"), artist(640, name = "Skeete")))

        assertNull(source(api).profileFor("Skepta").imageUrl)
    }

    @Test
    fun `a pinned artist is fetched by id instead of searched`() = runTest {
        val api = FakeApi(listOf(artist(640, name = "Dixon Dallas")))
        api.byId = artist(640, name = "Dixon", spotifyUrl = "https://open.spotify.com/artist/right")

        val profile = source(api).profileFor("Dixon")

        assertEquals(listOf("3wc57nV2fGEoM8x4xPK1O9"), api.fetchedIds)
        assertEquals("https://open.spotify.com/artist/right", profile.spotifyUrl)
        assertEquals(0, api.calls)
    }

    @Test
    fun `a network failure degrades to null instead of propagating`() = runTest {
        val failing = object : SpotifyApi {
            override suspend fun searchArtist(query: String, limit: Int): SpotifySearchResponse =
                throw IOException("offline")

            override suspend fun artist(id: String): SpotifyArtist = throw IOException("offline")
        }

        assertNull(source(failing).profileFor("Playboi Carti").imageUrl)
    }
}
