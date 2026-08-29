package dev.abhinav.artistpin.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.SyncOutboxEntity
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.FakeBackendApi
import dev.abhinav.artistpin.data.RoomBackupRepository
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The replay half. Ordering and the refusal to pull over unsent work are the two things that, if
 * wrong, silently lose changes rather than failing loudly.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibrarySyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeBackendApi()

    private lateinit var database: ArtistPinDatabase
    private lateinit var sync: LibrarySync

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ArtistPinDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()

        sync = LibrarySync(
            outbox = database.syncOutboxDao(),
            api = api,
            localBackup = RoomBackupRepository(
                database = database,
                concertDao = database.concertDao(),
                artistDao = database.artistDao(),
                mediaDao = database.mediaDao(),
                json = json,
                ioDispatcher = testDispatcher,
            ),
            json = json,
            ioDispatcher = testDispatcher,
        )

        // An empty library is a valid server response, and the default one here.
        api.exportPayload = json.parseToJsonElement(
            """{"version":1,"cities":[],"venues":[],"artists":[],"events":[],
                "eventArtists":[],"media":[]}""",
        ).jsonObject
    }

    @After
    fun tearDown() = database.close()

    private suspend fun queueSave(eventId: String) = database.syncOutboxDao().enqueue(
        SyncOutboxEntity(
            operation = SyncOperation.SAVE_EVENT.name,
            entityId = eventId,
            payloadJson = json.encodeToString(
                SaveEventPayload.serializer(),
                SaveEventPayload(
                    eventId = eventId,
                    eventDate = "2025-06-14",
                    cityName = "Queens",
                    country = "United States",
                    venueName = "Knockdown Center",
                    latitude = 40.71,
                    longitude = -73.92,
                    artistNames = listOf("Bedouin"),
                ),
            ),
            queuedAtEpochMillis = 0,
        ),
    )

    @Test
    fun `queued changes are sent and cleared`() = runTest(testDispatcher) {
        queueSave("e1")
        queueSave("e2")

        val result = (sync.syncNow() as DataResult.Success).data

        assertEquals(2, result.pushed)
        assertEquals(2, api.saveCallCount)
        assertTrue(database.syncOutboxDao().pending().isEmpty())
        assertTrue(result.isFullySynced)
    }

    @Test
    fun `a failure stops the drain and keeps the rest queued`() = runTest(testDispatcher) {
        queueSave("e1")
        queueSave("e2")
        api.failNextSave = FakeBackendApi.offline()

        val result = (sync.syncNow() as DataResult.Success).data

        // Stopping rather than skipping: the queue is ordered, and a later entry may depend on an
        // earlier one having landed.
        assertEquals(0, result.pushed)
        assertEquals(2, result.stillPending)
        assertFalse(result.isFullySynced)
    }

    @Test
    fun `a failed entry records the error rather than vanishing`() = runTest(testDispatcher) {
        queueSave("e1")
        api.failNextSave = FakeBackendApi.offline()

        sync.syncNow()

        val entry = database.syncOutboxDao().pending().single()
        assertEquals(1, entry.attempts)
        assertTrue(entry.lastError != null)
    }

    @Test
    fun `no pull happens while changes are still queued`() = runTest(testDispatcher) {
        queueSave("e1")
        api.failNextSave = FakeBackendApi.offline()

        val result = (sync.syncNow() as DataResult.Success).data

        // The critical one. A refresh here would overwrite the local library with the server's
        // older copy, destroying the very change that is waiting to be sent.
        assertFalse(result.pulled)
    }

    @Test
    fun `a pull happens once the queue is empty`() = runTest(testDispatcher) {
        val result = (sync.syncNow() as DataResult.Success).data

        assertTrue(result.pulled)
    }

    @Test
    fun `a rename resolves the old name through the catalog before renaming`() =
        runTest(testDispatcher) {
            database.syncOutboxDao().enqueue(
                SyncOutboxEntity(
                    operation = SyncOperation.RENAME_ARTIST.name,
                    entityId = "local-artist-id",
                    payloadJson = json.encodeToString(
                        RenameArtistPayload.serializer(),
                        RenameArtistPayload("Nghtmare", "NGHTMRE"),
                    ),
                    queuedAtEpochMillis = 0,
                ),
            )

            sync.syncNow()

            // "local-artist-id" is a Room id the shared catalog has never seen, so the replay has
            // to go via the name.
            assertEquals(listOf("Nghtmare"), api.findOrCreateCalls)
            assertEquals("catalog-id-for-nghtmare" to "NGHTMRE", api.renamedTo)
        }

    @Test
    fun `a pull replaces the local library with the server's`() = runTest(testDispatcher) {
        api.exportPayload = json.parseToJsonElement(
            """
            {"version":1,
             "cities":[{"id":"c1","name":"Queens","country":"United States"}],
             "venues":[{"id":"v1","name":"Knockdown Center","cityId":"c1",
                        "latitude":40.71,"longitude":-73.92}],
             "artists":[{"id":"a1","name":"Bedouin"}],
             "events":[{"id":"e1","venueId":"v1","dateEpochDay":20253}],
             "eventArtists":[{"eventId":"e1","artistId":"a1","billing":"HEADLINER"}],
             "media":[]}
            """.trimIndent(),
        ).jsonObject

        val result = (sync.syncNow() as DataResult.Success).data

        assertTrue(result.pulled)
        // Room now holds the server's ids for catalog rows, which is what keeps the two sides
        // agreeing without an id-mapping table.
        assertEquals(1, database.concertDao().allEvents().size)
        assertEquals("a1", database.artistDao().allArtists().single().id)
    }
}
