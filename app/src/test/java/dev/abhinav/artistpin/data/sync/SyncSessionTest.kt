package dev.abhinav.artistpin.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.auth.AuthState
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.SyncOutboxEntity
import dev.abhinav.artistpin.core.model.DataError
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
 * A missing session is not a refusal.
 *
 * Offline with an expired token, or a cold start before the session has loaded, used to make every
 * queued entry fail with "not signed in" — each one recorded against the retry budget, until photos
 * the server had never even seen were retired from the upload queue for good.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncSessionTest {

    private class RecordingScheduler : SyncScheduler {
        var requests = 0
        override fun requestSync() {
            requests++
        }
    }

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeBackendApi()
    private val auth = FakeAuthRepository()
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
            auth = auth,
            json = json,
            ioDispatcher = testDispatcher,
            scheduler = scheduler,
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun queueSomething() {
        database.syncOutboxDao().enqueue(
            SyncOutboxEntity(
                operation = SyncOperation.DELETE_EVENT.name,
                entityId = "event-1",
                payloadJson = json.encodeToString(
                    DeleteEventPayload.serializer(),
                    DeleteEventPayload("event-1"),
                ),
                queuedAtEpochMillis = 0,
            ),
        )
    }

    private fun assertDeferredWithoutCost(result: DataResult<SyncResult>) {
        assertTrue(result is DataResult.Failure)
        assertEquals(DataError.SyncUnavailable, (result as DataResult.Failure).error)
        // Asked to come back — that is what makes it recover once the session returns.
        assertEquals(1, scheduler.requests)
        // And nothing was sent, so nothing can have been refused.
        assertTrue(api.deletedEventIds.isEmpty())
    }

    @Test
    fun `an expired session offline defers the sync and counts nothing`() = runTest(testDispatcher) {
        queueSomething()
        auth.setState(AuthState.Unavailable)

        val result = sync.syncNow()

        assertDeferredWithoutCost(result)
        val entry = database.syncOutboxDao().pending().single()
        assertEquals("no attempt may be charged for a session problem", 0, entry.attempts)
    }

    @Test
    fun `a cold start before the session loads defers too`() = runTest(testDispatcher) {
        queueSomething()
        auth.setState(AuthState.Unknown)

        assertDeferredWithoutCost(sync.syncNow())
        assertEquals(0, database.syncOutboxDao().pending().single().attempts)
    }

    @Test
    fun `repeated deferrals never retire anything`() = runTest(testDispatcher) {
        queueSomething()
        auth.setState(AuthState.Unavailable)

        // Far past MAX_ATTEMPTS. Before the fix, five of these abandoned the entry.
        repeat(LibrarySync.MAX_ATTEMPTS * 3) { sync.syncNow() }

        assertEquals(1, database.syncOutboxDao().pendingCount())
        assertEquals(0, database.syncOutboxDao().pending().single().attempts)
    }

    @Test
    fun `once the session is back the queue drains normally`() = runTest(testDispatcher) {
        queueSomething()
        auth.setState(AuthState.Unavailable)
        sync.syncNow()

        auth.setState(AuthState.SignedIn("user-1", "user@example.com"))
        val result = sync.syncNow()

        assertTrue(result is DataResult.Success)
        assertEquals(listOf("event-1"), api.deletedEventIds)
        assertEquals(0, database.syncOutboxDao().pendingCount())
    }
}
