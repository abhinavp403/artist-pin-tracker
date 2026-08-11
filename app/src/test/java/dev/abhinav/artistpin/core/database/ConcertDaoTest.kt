package dev.abhinav.artistpin.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.model.Billing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ConcertDaoTest {

    private lateinit var database: ArtistPinDatabase
    private lateinit var concertDao: ConcertDao
    private lateinit var artistDao: ArtistDao
    private lateinit var mediaDao: MediaDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ArtistPinDatabase::class.java,
        ).allowMainThreadQueries().build()
        concertDao = database.concertDao()
        artistDao = database.artistDao()
        mediaDao = database.mediaDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `city pin sits at the centroid of its venues and counts distinct events`() = runTest {
        seedCity("toronto", "Toronto", "Canada")
        seedVenue("history", "History", "toronto", lat = 43.0, lng = -79.0)
        seedVenue("rebel", "Rebel", "toronto", lat = 45.0, lng = -81.0)
        seedEvent("e1", "history", LocalDate.of(2024, 7, 12))
        seedEvent("e2", "rebel", LocalDate.of(2025, 1, 5))
        seedEvent("e3", "rebel", LocalDate.of(2025, 3, 9))

        val pin = concertDao.observeCityPins().first().single()

        assertEquals(44.0, pin.latitude, 0.0001)
        assertEquals(-80.0, pin.longitude, 0.0001)
        assertEquals(3, pin.eventCount)
        assertEquals(2, pin.venueCount)
        assertEquals(LocalDate.of(2025, 3, 9).toEpochDay(), pin.lastEventEpochDay)
    }

    @Test
    fun `a city with no events never reaches the world map`() = runTest {
        seedCity("berlin", "Berlin", "Germany")
        seedVenue("berghain", "Berghain", "berlin", lat = 52.5, lng = 13.4)

        assertTrue(concertDao.observeCityPins().first().isEmpty())
    }

    @Test
    fun `venue pins are scoped to their city`() = runTest {
        seedCity("toronto", "Toronto", "Canada")
        seedCity("chicago", "Chicago", "USA")
        seedVenue("history", "History", "toronto", lat = 43.0, lng = -79.0)
        seedVenue("aragon", "Aragon Ballroom", "chicago", lat = 41.0, lng = -87.0)
        seedEvent("e1", "history", LocalDate.of(2024, 7, 12))
        seedEvent("e2", "aragon", LocalDate.of(2024, 8, 1))

        val pins = concertDao.observeVenuePins("toronto").first()

        assertEquals(listOf("History"), pins.map { it.venue.name })
        assertEquals(1, pins.single().eventCount)
    }

    @Test
    fun `event summary carries artists, venue, city and a thumbnail`() = runTest {
        seedCity("toronto", "Toronto", "Canada")
        seedVenue("history", "History", "toronto", lat = 43.0, lng = -79.0)
        seedEvent("e1", "history", LocalDate.of(2024, 7, 12))
        artistDao.upsertArtists(
            listOf(ArtistEntity("a1", "The Weeknd"), ArtistEntity("a2", "Mike Dean")),
        )
        concertDao.insertEventArtists(
            listOf(
                EventArtistCrossRef("e1", "a1", Billing.HEADLINER),
                EventArtistCrossRef("e1", "a2", Billing.SUPPORT),
            ),
        )
        mediaDao.upsertMedia(
            listOf(
                mediaEntity("m2", "e1", "/tmp/second.jpg", sortIndex = 1),
                mediaEntity("m1", "e1", "/tmp/first.jpg", sortIndex = 0),
            ),
        )

        val summary = concertDao.observeAllEvents().first().single()

        assertEquals("History", summary.venueName)
        assertEquals("Toronto", summary.cityName)
        assertEquals(2, summary.mediaCount)
        assertEquals("/tmp/first.jpg", summary.thumbnailPath)
        // Split by billing so headliners can be ordered ahead of support acts.
        assertEquals("The Weeknd", summary.headlinerNames)
        assertEquals("Mike Dean", summary.supportNames)
    }

    @Test
    fun `deleting an event cascades to media and prunes the orphaned venue and city`() = runTest {
        seedCity("toronto", "Toronto", "Canada")
        seedVenue("history", "History", "toronto", lat = 43.0, lng = -79.0)
        seedEvent("e1", "history", LocalDate.of(2024, 7, 12))
        mediaDao.upsertMedia(listOf(mediaEntity("m1", "e1", "/tmp/first.jpg", sortIndex = 0)))

        concertDao.deleteEventAndPrune("e1")

        assertTrue(mediaDao.getMediaForEvent("e1").isEmpty())
        assertNull(concertDao.getVenue("history"))
        assertNull(concertDao.getCity("toronto"))
    }

    @Test
    fun `event details resolve venue, city, artists and media in one read`() = runTest {
        seedCity("toronto", "Toronto", "Canada")
        seedVenue("history", "History", "toronto", lat = 43.0, lng = -79.0)
        seedEvent("e1", "history", LocalDate.of(2024, 7, 12))
        artistDao.upsertArtists(listOf(ArtistEntity("a1", "The Weeknd")))
        concertDao.insertEventArtists(listOf(EventArtistCrossRef("e1", "a1", Billing.HEADLINER)))

        val details = concertDao.observeEventDetails("e1").first()

        assertNotNull(details)
        assertEquals("Toronto", details!!.venue.city.name)
        assertEquals("History", details.venue.venue.name)
        assertEquals(listOf("The Weeknd"), details.artists.map { it.name })
    }

    private suspend fun seedCity(id: String, name: String, country: String) =
        concertDao.upsertCity(CityEntity(id = id, name = name, country = country))

    private suspend fun seedVenue(id: String, name: String, cityId: String, lat: Double, lng: Double) =
        concertDao.upsertVenue(VenueEntity(id, name, cityId, lat, lng))

    private suspend fun seedEvent(id: String, venueId: String, date: LocalDate) =
        concertDao.upsertEvent(EventEntity(id = id, venueId = venueId, dateEpochDay = date.toEpochDay()))

    private fun mediaEntity(id: String, eventId: String, path: String, sortIndex: Int) =
        EventMediaEntity(
            id = id,
            eventId = eventId,
            localPath = path,
            originalUri = "content://picker/$id",
            mimeType = "image/jpeg",
            sortIndex = sortIndex,
        )
}
