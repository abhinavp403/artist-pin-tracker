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

    private class FakeAuthApi(var tokensIssued: Int = 0) : SpotifyAuthApi {
        var expiresIn = 3600L
        override suspend fun token(basicAuth: String, grantType: String): SpotifyTokenResponse {
            tokensIssued++
            return SpotifyTokenResponse("token-$tokensIssued", expiresIn)
        }
    }

    private class FakeApi(private val results: List<SpotifyArtist>) : SpotifyApi {
        constructor(artist: SpotifyArtist?) : this(listOfNotNull(artist))

        var calls = 0
        var fetchedIds = mutableListOf<String>()
        var byId: SpotifyArtist? = null

        override suspend fun searchArtist(
            bearer: String,
            query: String,
            type: String,
            limit: Int,
        ): SpotifySearchResponse {
            calls++
            return SpotifySearchResponse(SpotifyArtistPage(results))
        }

        override suspend fun artist(bearer: String, id: String): SpotifyArtist {
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

    private fun source(
        api: SpotifyApi,
        auth: SpotifyAuthApi = FakeAuthApi(),
        id: String = "id",
        secret: String = "secret",
        clock: () -> Long = { 0L },
    ) = SpotifyArtistImageSource(api, SpotifyTokenProvider(auth, id, secret, clock))

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

    @Test
    fun `without credentials it never calls the API`() = runTest {
        val api = FakeApi(artist(640))

        assertNull(source(api, id = "", secret = "").profileFor("Playboi Carti").imageUrl)
        assertEquals(0, api.calls)
    }

    /** A burst of artist lookups must not fetch a token each time. */
    @Test
    fun `the token is cached across lookups`() = runTest {
        val auth = FakeAuthApi()
        val source = source(FakeApi(artist(640)), auth)

        repeat(5) { source.profileFor("Playboi Carti") }

        assertEquals(1, auth.tokensIssued)
    }

    @Test
    fun `an expired token is refetched`() = runTest {
        val auth = FakeAuthApi().apply { expiresIn = 60 }
        var now = 0L
        val source = source(FakeApi(artist(640)), auth, clock = { now })

        source.profileFor("Playboi Carti")
        now = 120_000
        source.profileFor("Playboi Carti")

        assertEquals(2, auth.tokensIssued)
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
            override suspend fun searchArtist(
                bearer: String,
                query: String,
                type: String,
                limit: Int,
            ): SpotifySearchResponse = throw IOException("offline")

            override suspend fun artist(bearer: String, id: String): SpotifyArtist =
                throw IOException("offline")
        }

        assertNull(source(failing).profileFor("Playboi Carti").imageUrl)
    }
}
