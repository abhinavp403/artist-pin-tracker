package dev.abhinav.artistpin.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.getOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConcertRepositoryTest {

    /** One dispatcher, shared with every `runTest` below — two schedulers would deadlock. */
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository
    private val imageSource = FakeArtistImageSource.withImages(mapOf("The Weeknd" to "https://img/weeknd.jpg"))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ArtistPinDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        repository = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = imageSource,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `saving a show creates its city, venue and artists and surfaces it as a pin`() = runTest(testDispatcher) {
        val result = repository.saveEvent(draft())

        assertTrue(result is DataResult.Success)
        val pin = repository.observeCityPins().first().single()
        assertEquals("Toronto", pin.city.name)
        assertEquals(1, pin.eventCount)
        assertEquals(43.6532, pin.latitude, 0.0001)
    }

    @Test
    fun `a show with no artist is rejected as validation, not a crash`() = runTest(testDispatcher) {
        val result = repository.saveEvent(draft(artists = emptyList()))

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).error is DataError.Validation)
    }

    /**
     * The edit screen blocks these before they reach here, but the repository is the boundary
     * that has to hold for any other caller — so it rejects the same set the UI does.
     */
    @Test
    fun `every requirement the edit screen enforces is rejected here too`() = runTest(testDispatcher) {
        val invalidDrafts = mapOf(
            "no artist" to draft(artists = emptyList()),
            "blank venue" to draft().copy(venueName = "  "),
            "blank city" to draft().copy(cityName = ""),
            "blank country" to draft().copy(country = ""),
            "latitude out of range" to draft().copy(latitude = 91.0),
            "longitude out of range" to draft().copy(longitude = -181.0),
            "NaN coordinates" to draft().copy(latitude = Double.NaN, longitude = Double.NaN),
        )

        invalidDrafts.forEach { (case, invalid) ->
            val result = repository.saveEvent(invalid)
            assertTrue("$case should fail", result is DataResult.Failure)
            assertTrue(
                "$case should be a validation error",
                (result as DataResult.Failure).error is DataError.Validation,
            )
        }

        assertTrue(repository.observeCityPins().first().isEmpty())
    }

    @Test
    fun `artist names are matched case-insensitively so one artist never splits in two`() = runTest(testDispatcher) {
        repository.saveEvent(draft(artists = listOf("The Weeknd")))
        repository.saveEvent(draft(artists = listOf("the weeknd"), date = LocalDate.of(2025, 2, 2)))

        val summaries = repository.observeArtistSummaries().first()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries.single().timesSeen)
    }

    @Test
    fun `editing a show keeps its id and replaces its artists`() = runTest(testDispatcher) {
        val id = (repository.saveEvent(draft(artists = listOf("Fred again.."))) as DataResult.Success).data

        repository.saveEvent(draft(eventId = id, artists = listOf("Skrillex"), existingCityId = null))

        val event = repository.observeEvent(id).first()!!
        assertEquals(listOf("Skrillex"), event.artists.map { it.artist.name })
        assertEquals(1, repository.observeAllEvents().first().size)
    }

    @Test
    fun `support acts are stored with their billing`() = runTest(testDispatcher) {
        val id = (
            repository.saveEvent(
                draft(artists = listOf("The Weeknd"), support = listOf("Mike Dean")),
            ) as DataResult.Success
            ).data

        val event = repository.observeEvent(id).first()!!
        assertEquals(listOf("The Weeknd"), event.headliners.map { it.name })
        assertEquals(
            Billing.SUPPORT,
            event.artists.single { it.artist.name == "Mike Dean" }.billing,
        )
    }

    /**
     * Regression: support acts used to sort ahead of headliners, because Room's @Relation and
     * GROUP_CONCAT both return rows in arbitrary order.
     */
    @Test
    fun `headliners always come before support acts`() = runTest(testDispatcher) {
        // "Playboi Carti" sorts before "The Weeknd" alphabetically, so a naive order puts the
        // support act first — exactly the bug this pins.
        val id = (
            repository.saveEvent(
                draft(artists = listOf("The Weeknd"), support = listOf("Playboi Carti")),
            ) as DataResult.Success
            ).data

        val event = repository.observeEvent(id).first()!!
        assertEquals(
            listOf("The Weeknd", "Playboi Carti"),
            event.artists.map { it.artist.name },
        )
        assertEquals("The Weeknd", event.displayTitle)

        val summary = repository.observeAllEvents().first().single()
        assertEquals(listOf("The Weeknd", "Playboi Carti"), summary.artistNames)
        assertEquals("The Weeknd", summary.displayTitle)
    }

    /**
     * Regression: the edit screen had no title field, so every save sent title = null and
     * re-saving a named show like "Circoloco" silently wiped its name.
     */
    @Test
    fun `a show title survives an edit`() = runTest(testDispatcher) {
        val id = (
            repository.saveEvent(
                draft(artists = listOf("Maceo Plex")).copy(title = "Circoloco"),
            ) as DataResult.Success
            ).data
        assertEquals("Circoloco", repository.observeEvent(id).first()!!.displayTitle)

        // Same show saved again, as the edit screen does.
        repository.saveEvent(
            draft(eventId = id, artists = listOf("Maceo Plex")).copy(title = "Circoloco"),
        )

        assertEquals("Circoloco", repository.observeEvent(id).first()!!.title)
    }

    @Test
    fun `clearing the title falls back to the headliner names`() = runTest(testDispatcher) {
        val id = (
            repository.saveEvent(
                draft(artists = listOf("Maceo Plex")).copy(title = "Circoloco"),
            ) as DataResult.Success
            ).data

        repository.saveEvent(draft(eventId = id, artists = listOf("Maceo Plex")).copy(title = null))

        val event = repository.observeEvent(id).first()!!
        assertEquals(null, event.title)
        assertEquals("Maceo Plex", event.displayTitle)
    }

    /** Regression: a typed-in city name used to insert a second row, silently failing the save. */
    @Test
    fun `a second show typed into the same city and venue reuses both`() = runTest(testDispatcher) {
        repository.saveEvent(draft(artists = listOf("Fred again..")))
        val second = repository.saveEvent(
            draft(artists = listOf("Skrillex"), date = LocalDate.of(2025, 5, 1)),
        )

        assertTrue(second is DataResult.Success)
        val pin = repository.observeCityPins().first().single()
        assertEquals(2, pin.eventCount)
        assertEquals(1, pin.venueCount)
    }

    /**
     * Regression: artwork backfill used to be a one-shot call in the map's init. The map is the
     * start destination, so it ran before any artist existed and never saw ones added later —
     * which looked exactly like the lookup being broken.
     */
    @Test
    fun `artwork is fetched for artists added after the backfill starts`() = runTest(testDispatcher) {
        val job = launch { repository.keepArtistArtworkFresh() }
        advanceUntilIdle()
        assertTrue("nothing to look up yet", imageSource.lookups.isEmpty())

        repository.saveEvent(draft(artists = listOf("The Weeknd")))
        advanceUntilIdle()

        assertEquals(listOf("The Weeknd"), imageSource.lookups)
        assertEquals(
            "https://img/weeknd.jpg",
            repository.observeArtistSummaries().first().single().artist.imageUrl,
        )
        job.cancel()
    }

    @Test
    fun `genres and the spotify link are stored alongside the portrait`() = runTest(testDispatcher) {
        val source = FakeArtistImageSource(
            mapOf(
                "Boris Brejcha" to dev.abhinav.artistpin.core.model.ArtistProfile(
                    imageUrl = "https://img/boris.jpg",
                    genres = listOf("melodic techno", "minimal techno"),
                    spotifyUrl = "https://open.spotify.com/artist/abc",
                ),
            ),
        )
        val repo = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(
                ApplicationProvider.getApplicationContext(), testDispatcher,
            ),
            artistImageSource = source,
            ioDispatcher = testDispatcher,
        )
        val job = launch { repo.keepArtistArtworkFresh() }

        repo.saveEvent(draft(artists = listOf("Boris Brejcha")))
        advanceUntilIdle()

        val artist = repo.observeArtistSummaries().first().single().artist
        assertEquals("https://img/boris.jpg", artist.imageUrl)
        assertEquals(listOf("melodic techno", "minimal techno"), artist.genres)
        assertEquals("https://open.spotify.com/artist/abc", artist.spotifyUrl)
        job.cancel()
    }

    /**
     * Being offline must not be cached as "this artist has no genres" — that would permanently
     * blank everyone from a single bad launch.
     */
    @Test
    fun `a failed lookup stays retryable instead of caching as empty`() = runTest(testDispatcher) {
        val failing = object : ArtistImageSource {
            var calls = 0
            override suspend fun profileFor(artistName: String) =
                dev.abhinav.artistpin.core.model.ArtistProfile(lookupFailed = true)
                    .also { calls++ }
        }
        val repo = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(ApplicationProvider.getApplicationContext(), testDispatcher),
            artistImageSource = failing,
            ioDispatcher = testDispatcher,
        )
        val job = launch { repo.keepArtistArtworkFresh() }

        repo.saveEvent(draft(artists = listOf("Boris Brejcha")))
        advanceUntilIdle()
        job.cancel()

        // Still unfetched, so a later run picks it up again.
        val artist = repo.observeArtistSummaries().first().single().artist
        assertTrue("genres must stay unset", artist.genres.isEmpty())
        val retried = launch { repo.keepArtistArtworkFresh() }
        advanceUntilIdle()
        assertTrue("the artist should be looked up again", failing.calls >= 2)
        retried.cancel()
    }

    /** An artist genuinely absent everywhere is cached so it is not re-queried forever. */
    @Test
    fun `an artist with nothing found is marked looked-up and not retried`() = runTest(testDispatcher) {
        val empty = object : ArtistImageSource {
            var calls = 0
            override suspend fun profileFor(artistName: String) =
                dev.abhinav.artistpin.core.model.ArtistProfile().also { calls++ }
        }
        val repo = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(ApplicationProvider.getApplicationContext(), testDispatcher),
            artistImageSource = empty,
            ioDispatcher = testDispatcher,
        )
        val job = launch { repo.keepArtistArtworkFresh() }
        repo.saveEvent(draft(artists = listOf("Kinahau")))
        advanceUntilIdle()
        job.cancel()

        val after = launch { repo.keepArtistArtworkFresh() }
        advanceUntilIdle()
        assertEquals("a genuine blank is cached, not re-queried", 1, empty.calls)
        after.cancel()
    }

    /** An artist nobody has a photo of must not be re-queried on every emission. */
    @Test
    fun `an artist with no artwork anywhere is only looked up once`() = runTest(testDispatcher) {
        val job = launch { repository.keepArtistArtworkFresh() }

        repository.saveEvent(draft(artists = listOf("Unknown Local Act")))
        advanceUntilIdle()
        repository.saveEvent(
            draft(artists = listOf("Another Act"), date = LocalDate.of(2025, 3, 3)),
        )
        advanceUntilIdle()

        assertEquals(1, imageSource.lookups.count { it == "Unknown Local Act" })
        job.cancel()
    }

    @Test
    fun `renaming an artist onto an existing name merges the two rather than duplicating`() =
        runTest(testDispatcher) {
            repository.saveEvent(draft(artists = listOf("Fred Again")))
            repository.saveEvent(
                draft(artists = listOf("fred again.."), date = LocalDate.of(2025, 2, 2)),
            )
            val typo = repository.observeArtistSummaries().first().single { it.artist.name == "Fred Again" }

            repository.renameArtist(typo.artist.id, "fred again..")

            val summaries = repository.observeArtistSummaries().first()
            assertEquals(1, summaries.size)
            assertEquals("fred again..", summaries.single().artist.name)
            assertEquals(2, summaries.single().timesSeen)
        }

    @Test
    fun `deleting an artist keeps shows that had someone else on the bill`() = runTest(testDispatcher) {
        repository.saveEvent(draft(artists = listOf("The Weeknd"), support = listOf("Playboi Carti")))
        val support = repository.observeArtistSummaries().first().single { it.artist.name == "Playboi Carti" }

        val impact = repository.previewArtistDeletion(support.artist.id).getOrNull()!!
        assertEquals(1, impact.showsAffected)
        assertEquals(0, impact.showsDeleted)

        repository.deleteArtist(support.artist.id)

        val event = repository.observeAllEvents().first().single()
        assertEquals(listOf("The Weeknd"), event.artistNames)
    }

    @Test
    fun `deleting the last artist on a show removes the show and prunes its city`() =
        runTest(testDispatcher) {
            repository.saveEvent(draft(artists = listOf("The Weeknd")))
            val only = repository.observeArtistSummaries().first().single()

            val impact = repository.previewArtistDeletion(only.artist.id).getOrNull()!!
            assertEquals(1, impact.showsDeleted)

            repository.deleteArtist(only.artist.id)

            assertTrue(repository.observeAllEvents().first().isEmpty())
            assertTrue(repository.observeCityPins().first().isEmpty())
        }

    @Test
    fun `deleting the only show at a venue clears the city off the map`() = runTest(testDispatcher) {
        val id = (repository.saveEvent(draft()) as DataResult.Success).data

        repository.deleteEvent(id)

        assertTrue(repository.observeCityPins().first().isEmpty())
        assertTrue(repository.observeArtistSummaries().first().isEmpty())
    }

    private fun draft(
        eventId: String? = null,
        artists: List<String> = listOf("The Weeknd"),
        support: List<String> = emptyList(),
        date: LocalDate = LocalDate.of(2024, 7, 12),
        existingCityId: String? = null,
    ) = EventDraft(
        eventId = eventId,
        date = date,
        cityName = "Toronto",
        country = "Canada",
        existingCityId = existingCityId,
        venueName = "History",
        latitude = 43.6532,
        longitude = -79.3832,
        artistNames = artists,
        supportArtistNames = support,
    )
}
