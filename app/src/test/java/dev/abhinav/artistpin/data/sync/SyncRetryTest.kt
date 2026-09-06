package dev.abhinav.artistpin.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.SyncOutboxEntity
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.FakeAuthRepository
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
 * A sync that ends incomplete has to ask for another one.
 *
 * The case that motivated this: signing in on a new phone. That first run is a pure pull with an
 * empty queue, so no write ever asks the scheduler for it — and if it fails, nothing retries until
 * the user happens to reopen the app, which they have no reason to do because the app looks empty
 * rather than broken.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncRetryTest {

    private class RecordingScheduler : SyncScheduler {
        var requests = 0
        override fun requestSync() {
            requests++
        }
    }

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeBackendApi()
    private val scheduler = RecordingScheduler()

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
            mediaDao = database.mediaDao(),
            auth = FakeAuthRepository(),
            json = json,
            ioDispatcher = testDispatcher,
            scheduler = scheduler,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `a failed first pull schedules its own retry`() = runTest(testDispatcher) {
        // Nothing queued — exactly the shape of a fresh install's first sync.
        api.failNextRead = FakeBackendApi.offline()

        val result = sync.syncNow()

        assertTrue(result is DataResult.Failure)
        // Without this the app sits empty until the user reopens it, with no reason to think
        // reopening would help.
        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `a run that leaves changes queued asks to come back`() = runTest(testDispatcher) {
        api.failDeleteEventFor += "stuck"
        database.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                operation = SyncOperation.DELETE_EVENT.name,
                entityId = "stuck",
                payloadJson = json.encodeToString(
                    DeleteEventPayload.serializer(),
                    DeleteEventPayload("stuck"),
                ),
                queuedAtEpochMillis = 0,
            ),
        )

        val result = (sync.syncNow() as DataResult.Success).data

        assertTrue(!result.isFullySynced)
        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `a clean run asks for nothing`() = runTest(testDispatcher) {
        sync.syncNow()

        // Requesting work after every successful sync would have WorkManager waking up for a queue
        // that is already empty.
        assertEquals(0, scheduler.requests)
    }
}
