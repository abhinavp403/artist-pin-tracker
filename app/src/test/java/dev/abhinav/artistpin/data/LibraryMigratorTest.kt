package dev.abhinav.artistpin.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.backend.ImportResultDto
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The migration runs once, against real data, and there is no undo — so the thing worth testing is
 * that what leaves the device is actually the library, in the shape `import_backup` expects.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryMigratorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val api = FakeBackendApi()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository
    private lateinit var migrator: LibraryMigrator

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
            artistImageSource = FakeArtistImageSource(),
            ioDispatcher = testDispatcher,
        )
        val backups = RoomBackupRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            json = json,
            ioDispatcher = testDispatcher,
        )
        migrator = LibraryMigrator(backups, api, json, testDispatcher)
    }

    @After
    fun tearDown() = database.close()

    private fun draft(artist: String, venue: String, city: String) = EventDraft(
        date = LocalDate.of(2025, 6, 14),
        cityName = city,
        country = "United States",
        venueName = venue,
        latitude = 40.71,
        longitude = -73.92,
        artistNames = listOf(artist),
    )

    @Test
    fun `the payload is a JSON object, not a string holding JSON`() = runTest(testDispatcher) {
        repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))

        migrator.uploadLocalLibrary()

        // The RPC parameter is jsonb. Posting the export as an encoded string would nest the whole
        // library inside one scalar, and every `payload -> 'events'` in the function would find
        // nothing — an import that reports success and moves zero shows.
        val payload = api.importedPayload!!
        assertEquals(1, payload["events"]!!.jsonArray.size)
        assertEquals("Queens", payload["cities"]!!.jsonArray.first().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every table the import needs is present in the payload`() = runTest(testDispatcher) {
        repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))

        migrator.uploadLocalLibrary()

        val payload = api.importedPayload!!
        // The function walks each of these by name; a missing key is a silently partial import.
        listOf("cities", "venues", "artists", "events", "eventArtists").forEach { table ->
            assertTrue("payload is missing '$table'", payload.containsKey(table))
        }
    }

    @Test
    fun `local ids are carried over so the import can key on them`() = runTest(testDispatcher) {
        val saved = repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))
        val eventId = (saved as DataResult.Success).data

        migrator.uploadLocalLibrary()

        // This is what makes a second run a no-op instead of a duplicate library — the event's
        // original id has to survive the trip.
        val sentId = api.importedPayload!!["events"]!!.jsonArray
            .first().jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(eventId, sentId)
    }

    @Test
    fun `the whole library goes, not just the first show`() = runTest(testDispatcher) {
        repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))
        repository.saveEvent(draft("Dixon", "Output", "Brooklyn"))

        migrator.uploadLocalLibrary()

        val payload = api.importedPayload!!
        assertEquals(2, payload["events"]!!.jsonArray.size)
        assertEquals(2, payload["artists"]!!.jsonArray.size)
        assertEquals(2, payload["cities"]!!.jsonArray.size)
    }

    @Test
    fun `counts come back from the server rather than being assumed`() = runTest(testDispatcher) {
        repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))
        // A second run finds everything already there. Reporting the local row count instead would
        // claim to have uploaded shows that were skipped.
        api.importResult = ImportResultDto(eventsImported = 0, eventsSkipped = 1)

        val result = migrator.uploadLocalLibrary()

        val summary = (result as DataResult.Success).data
        assertEquals(0, summary.imported)
        assertEquals(1, summary.skipped)
        assertTrue(!summary.nothingToDo)
    }

    @Test
    fun `an empty library reports nothing to do rather than success`() = runTest(testDispatcher) {
        val result = migrator.uploadLocalLibrary()

        assertTrue((result as DataResult.Success).data.nothingToDo)
    }

    @Test
    fun `a dropped connection fails without touching the local library`() = runTest(testDispatcher) {
        repository.saveEvent(draft("Bedouin", "Knockdown Center", "Queens"))
        api.failNextRead = FakeBackendApi.offline()

        val result = migrator.uploadLocalLibrary()

        assertTrue(result is DataResult.Failure)
        // The migration only ever reads locally — nothing is deleted or rewritten on the device,
        // so a failure halfway leaves the app exactly as it was and the action can be retried.
        assertEquals(1, repository.observeAllEvents().first().size)
    }
}
