package dev.abhinav.artistpin.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

// The source logs lookup failures, and android.util.Log is stubbed out on a bare JVM.
@RunWith(RobolectricTestRunner::class)
class DeezerArtistImageSourceTest {

    private fun sourceReturning(vararg artists: DeezerArtist) = DeezerArtistImageSource(
        object : DeezerApi {
            override suspend fun searchArtist(query: String, limit: Int) =
                DeezerSearchResponse(artists.toList())
        },
    )

    private fun artist(name: String, hash: String) = DeezerArtist(
        name = name,
        pictureBig = "https://cdn-images.dzcdn.net/images/artist/$hash/500x500-000000-80-0-0.jpg",
        pictureMedium = "https://cdn-images.dzcdn.net/images/artist/$hash/250x250-000000-80-0-0.jpg",
    )

    @Test
    fun `a real image hash is returned as-is`() = runTest {
        val url = sourceReturning(artist("The Weeknd", REAL_HASH)).profileFor("The Weeknd").imageUrl

        assertEquals(
            "https://cdn-images.dzcdn.net/images/artist/$REAL_HASH/500x500-000000-80-0-0.jpg",
            url,
        )
    }

    /**
     * Regression: the empty-string MD5 is Deezer's no-photo sentinel behind an otherwise valid
     * URL. Verified live — Playboi Carti returns it. Returning it would show a blank silhouette.
     */
    @Test
    fun `the empty-image hash is treated as no picture`() = runTest {
        assertNull(sourceReturning(artist("Playboi Carti", EMPTY_HASH)).profileFor("Playboi Carti").imageUrl)
    }

    @Test
    fun `the standard size suffix is not mistaken for a placeholder`() = runTest {
        // Every Deezer URL contains "-000000-80-0-0"; an earlier filter wrongly keyed on it.
        val url = sourceReturning(artist("Skrillex", REAL_HASH)).profileFor("Skrillex").imageUrl
        assertEquals(true, url?.contains("-000000-80-0-0"))
    }

    @Test
    fun `no match returns null rather than throwing`() = runTest {
        assertNull(sourceReturning().profileFor("Nobody At All").imageUrl)
    }

    /** The portrait fallback must be as strict as Spotify, or it reintroduces the wrong face. */
    @Test
    fun `a top hit with a different name is skipped for the exact match`() = runTest {
        val url = sourceReturning(artist("Dixon Dallas", EMPTY_HASH), artist("Dixon", REAL_HASH))
            .profileFor("Dixon")
            .imageUrl

        assertEquals(true, url?.contains(REAL_HASH))
    }

    @Test
    fun `a search with no exact match returns null`() = runTest {
        assertNull(sourceReturning(artist("Skeptika", REAL_HASH)).profileFor("Skepta").imageUrl)
    }

    @Test
    fun `a network failure degrades to null instead of propagating`() = runTest {
        val source = DeezerArtistImageSource(
            object : DeezerApi {
                override suspend fun searchArtist(query: String, limit: Int): DeezerSearchResponse =
                    throw IOException("offline")
            },
        )

        assertNull(source.profileFor("The Weeknd").imageUrl)
    }

    private companion object {
        const val REAL_HASH = "581693b4724a7fcfa754455101e13a44"
        const val EMPTY_HASH = "d41d8cd98f00b204e9800998ecf8427e"
    }
}
