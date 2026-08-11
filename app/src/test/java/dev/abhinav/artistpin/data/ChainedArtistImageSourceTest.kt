package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.ArtistProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChainedArtistImageSourceTest {

    private fun sourceReturning(
        url: String?,
        calls: MutableList<String>,
        tag: String,
        genres: List<String> = emptyList(),
        spotifyUrl: String? = null,
    ) = object : ArtistImageSource {
        override suspend fun profileFor(artistName: String): ArtistProfile {
            calls += tag
            return ArtistProfile(imageUrl = url, genres = genres, spotifyUrl = spotifyUrl)
        }
    }

    @Test
    fun `the first portrait wins over a later one`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning("spotify.jpg", calls, "spotify"),
                sourceReturning("deezer.jpg", calls, "deezer"),
            ),
        )

        assertEquals("spotify.jpg", chain.profileFor("The Weeknd").imageUrl)
    }

    /**
     * Genres live further down the chain than the portrait, so finding a portrait must not end
     * the walk — that is exactly what stopped genres arriving the first time round.
     */
    @Test
    fun `a portrait from the first source does not stop the search for genres`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning("spotify.jpg", calls, "spotify", spotifyUrl = "https://open/x"),
                sourceReturning(null, calls, "musicbrainz", genres = listOf("minimal techno")),
            ),
        )

        val profile = chain.profileFor("Boris Brejcha")
        assertEquals("spotify.jpg", profile.imageUrl)
        assertEquals(listOf("minimal techno"), profile.genres)
        assertEquals(listOf("spotify", "musicbrainz"), calls)
    }

    /** Once every field is filled there is nothing left to ask for. */
    @Test
    fun `a complete profile stops the chain early`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning("a.jpg", calls, "first", genres = listOf("techno"), spotifyUrl = "https://open/x"),
                sourceReturning("b.jpg", calls, "second"),
            ),
        )

        chain.profileFor("Anyma")
        assertEquals(listOf("first"), calls)
    }

    /** Spotify has real gaps, so Deezer has to be consulted rather than replaced. */
    @Test
    fun `a gap in the first source falls through to the next`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning(null, calls, "spotify"),
                sourceReturning("deezer.jpg", calls, "deezer"),
            ),
        )

        assertEquals("deezer.jpg", chain.profileFor("Playboi Carti").imageUrl)
        assertEquals(listOf("spotify", "deezer"), calls)
    }

    /** Genres come from the first source; a later portrait-only source must not drop them. */
    @Test
    fun `genres from the first source survive a fallback portrait from the second`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning(null, calls, "spotify", genres = listOf("melodic techno")),
                sourceReturning("deezer.jpg", calls, "deezer"),
            ),
        )

        val profile = chain.profileFor("Colyn")
        assertEquals("deezer.jpg", profile.imageUrl)
        assertEquals(listOf("melodic techno"), profile.genres)
    }

    @Test
    fun `null when nobody has a portrait, so the pin shows initials`() = runTest {
        val calls = mutableListOf<String>()
        val chain = ChainedArtistImageSource(
            listOf(
                sourceReturning(null, calls, "spotify"),
                sourceReturning(null, calls, "deezer"),
            ),
        )

        assertNull(chain.profileFor("Nobody").imageUrl)
    }
}
