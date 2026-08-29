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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The queue's failure behaviour. Every one of these is a way the drain could stop moving forever,
 * which is the worst outcome available to it: the app keeps saying "N changes waiting" and never
 * makes progress, and the user has no way to tell or to intervene.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncDrainTest {

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
    }

    @After
    fun tearDown() = database.close()

    private suspend fun queueDelete(eventId: String, attempts: Int = 0) {
        database.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                operation = SyncOperation.DELETE_EVENT.name,
                entityId = eventId,
                payloadJson = json.encodeToString(
                    DeleteEventPayload.serializer(),
                    DeleteEventPayload(eventId),
                ),
                queuedAtEpochMillis = 0,
                attempts = attempts,
            ),
        )
    }

    @Test
    fun `an entry that can never succeed is abandoned rather than blocking the queue`() =
        runTest(testDispatcher) {
            // Already tried MAX_ATTEMPTS - 1 times; this run is the last.
            api.failDeleteEventFor += "ghost"
            queueDelete("ghost", attempts = LibrarySync.MAX_ATTEMPTS - 1)
            queueDelete("real")

            val result = (sync.syncNow() as DataResult.Success).data

            // The poisonous entry is gone and the one behind it went through. Before the fix the
            // drain broke at the first entry and neither ever moved again.
            assertEquals(1, result.dropped)
            assertEquals(0, database.syncOutboxDao().pendingCount())
            // The entry behind it got its turn rather than inheriting the blockage.
            assertEquals(listOf("real"), api.deletedEventIds)
        }

    @Test
    fun `a first failure stops the drain rather than skipping ahead`() = runTest(testDispatcher) {
        api.failDeleteEventFor += setOf("first", "second")
        queueDelete("first")
        queueDelete("second")

        val result = (sync.syncNow() as DataResult.Success).data

        // Order matters: later entries assume earlier ones landed, so a retryable failure has to
        // hold the line rather than let the queue apply changes out of sequence.
        assertEquals(0, result.pushed)
        assertEquals(0, result.dropped)
        assertEquals(2, database.syncOutboxDao().pendingCount())
    }

    @Test
    fun `a pull never runs while changes are still queued`() = runTest(testDispatcher) {
        api.failDeleteEventFor += "stuck"
        queueDelete("stuck")

        val result = (sync.syncNow() as DataResult.Success).data

        // Refreshing here would overwrite the local library with the server's older copy and lose
        // the queued change outright.
        assertTrue(!result.pulled)
    }

    @Test
    fun `a clean queue pulls and reports it`() = runTest(testDispatcher) {
        val result = (sync.syncNow() as DataResult.Success).data

        assertTrue(result.pulled)
        assertTrue(result.isFullySynced)
    }
}
