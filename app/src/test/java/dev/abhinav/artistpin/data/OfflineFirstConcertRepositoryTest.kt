package dev.abhinav.artistpin.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.sync.DeleteEventPayload
import dev.abhinav.artistpin.data.sync.RenameArtistPayload
import dev.abhinav.artistpin.data.sync.SaveEventPayload
import dev.abhinav.artistpin.data.sync.SyncOperation
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The offline half of the contract: a save has to land locally and be replayable later, without
 * any network involved at the moment the user presses the button.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineFirstConcertRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ArtistPinDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()

        val local = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = FakeArtistImageSource(),
            ioDispatcher = testDispatcher,
        )
        repository = OfflineFirstConcertRepository(
            local = local,
            outbox = database.syncOutboxDao(),
            mediaDao = database.mediaDao(),
            json = json,
        )
    }

    @After
    fun tearDown() = database.close()

    private fun draft(artist: String = "Bedouin", venue: String = "Knockdown Center") = EventDraft(
        date = LocalDate.of(2025, 6, 14),
        cityName = "Queens",
        country = "United States",
        venueName = venue,
        latitude = 40.71,
        longitude = -73.92,
        artistNames = listOf(artist),
    )

    @Test
    fun `a saved show is readable immediately and queued for later`() = runTest(testDispatcher) {
        val saved = repository.saveEvent(draft())
        val eventId = (saved as DataResult.Success).data

        // Readable with no network in sight — this is the whole point of the milestone.
        assertEquals(1, repository.observeAllEvents().first().size)

        val queued = database.syncOutboxDao().pending().single()
        assertEquals(SyncOperation.SAVE_EVENT.name, queued.operation)
        assertEquals(eventId, queued.entityId)
    }

    @Test
    fun `the queued payload carries the id Room assigned, so a replay is not a duplicate`() =
        runTest(testDispatcher) {
            val eventId = (repository.saveEvent(draft()) as DataResult.Success).data

            val payload = json.decodeFromString(
                SaveEventPayload.serializer(),
                database.syncOutboxDao().pending().single().payloadJson,
            )
            // save_event inserts this id verbatim. Without it the server would mint its own and a
            // retried queue entry would become a second copy of the same show.
            assertEquals(eventId, payload.eventId)
            assertEquals("Knockdown Center", payload.venueName)
            assertEquals(listOf("Bedouin"), payload.artistNames)
        }

    @Test
    fun `editing the same show repeatedly queues one save, not one per edit`() =
        runTest(testDispatcher) {
            val eventId = (repository.saveEvent(draft()) as DataResult.Success).data
            repository.saveEvent(draft(artist = "Dixon").copy(eventId = eventId))
            repository.saveEvent(draft(artist = "Adriatique").copy(eventId = eventId))

            val pending = database.syncOutboxDao().pending()
            assertEquals(1, pending.size)
            // Each payload is the whole draft, so only the last one carries any information.
            val payload = json.decodeFromString(SaveEventPayload.serializer(), pending.single().payloadJson)
            assertEquals(listOf("Adriatique"), payload.artistNames)
        }

    @Test
    fun `deleting a show drops its own queued creation instead of replaying then undoing it`() =
        runTest(testDispatcher) {
            val eventId = (repository.saveEvent(draft()) as DataResult.Success).data

            repository.deleteEvent(eventId)

            val pending = database.syncOutboxDao().pending()
            // One entry, and it is the delete — not a create followed by a delete of a show the
            // server never saw.
            assertEquals(1, pending.size)
            assertEquals(SyncOperation.DELETE_EVENT.name, pending.single().operation)
            val payload = json.decodeFromString(DeleteEventPayload.serializer(), pending.single().payloadJson)
            assertEquals(eventId, payload.eventId)
        }

    @Test
    fun `a rename queues both names, because the local id means nothing to the catalog`() =
        runTest(testDispatcher) {
            repository.saveEvent(draft(artist = "Nghtmare"))
            val artistId = repository.observeKnownArtists().first().single().id
            database.syncOutboxDao().clear()

            repository.renameArtist(artistId, "NGHTMRE")

            val payload = json.decodeFromString(
                RenameArtistPayload.serializer(),
                database.syncOutboxDao().pending().single().payloadJson,
            )
            // The server resolves the old spelling through find-or-create; a Room id would name a
            // row the shared catalog has never heard of.
            assertEquals("Nghtmare", payload.previousName)
            assertEquals("NGHTMRE", payload.newName)
        }

    @Test
    fun `a failed local write queues nothing`() = runTest(testDispatcher) {
        val result = repository.saveEvent(draft().copy(artistNames = listOf("   ")))

        assertTrue(result is DataResult.Failure)
        // Queueing on failure would push a show that does not exist locally, and the two would
        // disagree forever.
        assertTrue(database.syncOutboxDao().pending().isEmpty())
    }

    @Test
    fun `two different shows queue two entries`() = runTest(testDispatcher) {
        repository.saveEvent(draft(venue = "Knockdown Center"))
        repository.saveEvent(draft(venue = "Output"))

        // Superseding is keyed on the event, so it must not collapse unrelated shows into one.
        assertEquals(2, database.syncOutboxDao().pending().size)
    }
}
