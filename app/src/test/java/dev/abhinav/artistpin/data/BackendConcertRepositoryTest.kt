package dev.abhinav.artistpin.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.backend.CityDto
import dev.abhinav.artistpin.data.backend.CityPinDto
import dev.abhinav.artistpin.data.backend.VenueDto
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Covers the machinery that has no counterpart in the Room implementation: manufactured Flow
 * emissions, and what happens to them when the network is not there.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackendConcertRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val api = FakeBackendApi()
    private lateinit var repository: ConcertRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        repository = BackendConcertRepository(
            api = api,
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = FakeArtistImageSource.withImages(emptyMap()),
            ioDispatcher = testDispatcher,
        )
    }

    private fun cityPin(name: String, events: Int) = CityPinDto(
        id = "c-$name",
        name = name,
        country = "United States",
        latitude = 40.7,
        longitude = -73.9,
        eventCount = events,
        venueCount = 1,
        lastEventDate = "2025-06-14",
    )

    private fun draft() = EventDraft(
        date = LocalDate.of(2025, 6, 14),
        cityName = "Queens",
        country = "United States",
        venueName = "Knockdown Center",
        latitude = 40.71,
        longitude = -73.92,
        artistNames = listOf("Bedouin"),
    )

    @Test
    fun `an open observer re-reads after a write`() = runTest(testDispatcher) {
        api.cityPins = listOf(cityPin("Queens", events = 1))

        repository.observeCityPins().test {
            assertEquals(1, awaitItem().single().eventCount)

            // PostgREST cannot push, so without the repository poking its refresh trigger this
            // second value would never arrive and the map would silently stay stale after a save.
            api.cityPins = listOf(cityPin("Queens", events = 2))
            repository.saveEvent(draft())

            assertEquals(2, awaitItem().single().eventCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed refresh holds the last good value instead of emptying the map`() =
        runTest(testDispatcher) {
            api.cityPins = listOf(cityPin("Queens", events = 1))

            repository.observeCityPins().test {
                assertEquals(1, awaitItem().single().eventCount)

                // The read fails, but the write that triggered it succeeded.
                api.failNextRead = FakeBackendApi.offline()
                repository.saveEvent(draft())

                // The point of the test: still one pin, not zero. Emitting empty here would draw
                // an empty map, which reads to the user as "your shows are gone" rather than
                // "we couldn't reach the server".
                assertEquals(1, awaitItem().single().eventCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a recovered connection produces fresh data on the next refresh`() =
        runTest(testDispatcher) {
            api.cityPins = listOf(cityPin("Queens", events = 1))

            repository.observeCityPins().test {
                awaitItem()

                api.failNextRead = FakeBackendApi.offline()
                repository.saveEvent(draft())
                assertEquals(1, awaitItem().single().eventCount)

                // failNextRead cleared itself, so this one goes through — the held value must not
                // become sticky.
                api.cityPins = listOf(cityPin("Queens", events = 5))
                repository.saveEvent(draft())
                assertEquals(5, awaitItem().single().eventCount)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a draft with no artists is rejected before it reaches the network`() =
        runTest(testDispatcher) {
            val result = repository.saveEvent(draft().copy(artistNames = listOf("  ")))

            assertTrue(result is DataResult.Failure)
            assertTrue((result as DataResult.Failure).error is DataError.Validation)
            // save_event re-checks this server-side, but a round trip to be told so is a worse
            // experience than an immediate field error.
            assertEquals(0, api.saveCallCount)
        }

    @Test
    fun `a dropped connection on a write is reported as a network failure, not a storage one`() =
        runTest(testDispatcher) {
            api.failNextRead = FakeBackendApi.offline()

            val result = repository.previewArtistDeletion("a1")

            assertTrue(result is DataResult.Failure)
            // The Room implementation maps IOException to MediaUnavailable, which would be a
            // nonsense message here — every write in this implementation is a network call.
            assertTrue((result as DataResult.Failure).error is DataError.Network)
        }

    @Test
    fun `the draft is trimmed and blank optional fields become null on the wire`() =
        runTest(testDispatcher) {
            repository.saveEvent(
                draft().copy(
                    cityName = "  Queens  ",
                    venueName = " Knockdown Center ",
                    title = "   ",
                    artistNames = listOf(" Bedouin ", ""),
                ),
            )

            val sent = api.savedParams!!
            assertEquals("Queens", sent.cityName)
            assertEquals("Knockdown Center", sent.venueName)
            assertNull(sent.title)
            assertEquals(listOf("Bedouin"), sent.artistNames)
        }

    @Test
    fun `no existing city or venue id is sent — resolving them is the backend's job`() =
        runTest(testDispatcher) {
            // The Room implementation looked these up client-side. Doing that against a shared
            // catalog would race: two people adding their first Queens show would both miss and
            // both insert. SaveEventParams has no field for them at all, by design.
            repository.saveEvent(draft())

            assertEquals("Queens", api.savedParams?.cityName)
            assertNull(api.savedParams?.eventId)
        }

    @Test
    fun `venues whose city failed to embed are dropped rather than shown detached`() =
        runTest(testDispatcher) {
            api.venues = listOf(
                VenueDto(
                    id = "v1",
                    name = "Knockdown Center",
                    cityId = "c1",
                    latitude = 40.71,
                    longitude = -73.92,
                    city = CityDto(id = "c1", name = "Queens", country = "United States"),
                ),
                VenueDto(
                    id = "v2",
                    name = "Orphan",
                    cityId = "c2",
                    latitude = 1.0,
                    longitude = 1.0,
                    city = null,
                ),
            )

            repository.observeKnownVenues().test {
                val venues = awaitItem()
                // The add form prefills a city from this list, so a venue with no city would
                // prefill blank and quietly produce a show in the wrong place.
                assertEquals(listOf("Knockdown Center"), venues.map { it.first.name })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- Regressions from the B6 code review ------------------------------------------------

    private fun detailDto(id: String) = dev.abhinav.artistpin.data.backend.EventDetailDto(
        id = id,
        eventDate = "2025-06-14",
        venue = dev.abhinav.artistpin.data.backend.VenueDto(
            id = "v1",
            name = "Knockdown Center",
            cityId = "c1",
            latitude = 40.71,
            longitude = -73.92,
            city = CityDto(id = "c1", name = "Queens", country = "United States"),
        ),
    )

    private fun summaryDto(id: String) = dev.abhinav.artistpin.data.backend.EventSummaryDto(
        id = id,
        eventDate = "2025-06-14",
        venueId = "v1",
        venueName = "Knockdown Center",
        cityId = "c1",
        cityName = "Queens",
    )

    /** Where MediaImporter keeps an event's files, so a test can prove they survived. */
    private fun mediaDirFor(eventId: String): java.io.File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return java.io.File(java.io.File(context.filesDir, "media"), eventId)
            .apply { mkdirs(); java.io.File(this, "photo.jpg").writeText("bytes") }
    }

    @Test
    fun `deleting an artist does not delete photos of a show whose lookup failed`() =
        runTest(testDispatcher) {
            val photos = mediaDirFor("e1")
            api.eventsForArtist = listOf(summaryDto("e1"))
            // The show survives the artist deletion, but checking that fails. Treating a failed
            // check as "the show is gone" would delete its photos irrecoverably — media never
            // leaves the device until Milestone D.
            api.events["e1"] = detailDto("e1")
            api.failEventLookupFor += "e1"

            val result = repository.deleteArtist("a1")

            assertTrue(result is DataResult.Success)
            assertEquals("a1", api.deletedArtistId)
            assertTrue("photos were deleted on an inconclusive check", photos.exists())
        }

    @Test
    fun `deleting an artist does delete photos of a show that is confirmed gone`() =
        runTest(testDispatcher) {
            val photos = mediaDirFor("e2")
            api.eventsForArtist = listOf(summaryDto("e2"))
            // No entry in api.events and no lookup failure: the show really was removed, so its
            // files are genuinely orphaned and should go.
            val result = repository.deleteArtist("a1")

            assertTrue(result is DataResult.Success)
            assertFalse("orphaned photos were left on disk", photos.exists())
        }

    @Test
    fun `deleting an artist aborts if the affected shows cannot be listed`() =
        runTest(testDispatcher) {
            api.failEventsForArtist = FakeBackendApi.offline()

            val result = repository.deleteArtist("a1")

            assertTrue(result is DataResult.Failure)
            // Nothing was destroyed: without that list we cannot tell which files the delete would
            // orphan, and either guess is worse than not starting.
            assertNull(api.deletedArtistId)
        }

    @Test
    fun `renaming repoints this user's shows without touching the shared catalog row`() =
        runTest(testDispatcher) {
            val result = repository.renameArtist("a1", "  NGHTMRE  ")

            assertTrue(result is DataResult.Success)
            assertEquals("a1" to "NGHTMRE", api.renamedTo)
        }
}
