package dev.abhinav.artistpin.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.EventDraft
import dev.abhinav.artistpin.data.FakeArtistImageSource
import dev.abhinav.artistpin.data.OfflineFirstConcertRepository
import dev.abhinav.artistpin.data.RoomConcertRepository
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
import java.time.LocalDate

/** Records requests instead of touching WorkManager, which is not the thing under test here. */
private class RecordingScheduler : SyncScheduler {
    var requests = 0
    override fun requestSync() {
        requests++
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SyncSchedulingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val scheduler = RecordingScheduler()

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

        repository = OfflineFirstConcertRepository(
            local = RoomConcertRepository(
                concertDao = database.concertDao(),
                artistDao = database.artistDao(),
                mediaDao = database.mediaDao(),
                mediaImporter = MediaImporter(context, testDispatcher),
                artistImageSource = FakeArtistImageSource(),
                ioDispatcher = testDispatcher,
            ),
            outbox = database.syncOutboxDao(),
            mediaDao = database.mediaDao(),
            json = Json { ignoreUnknownKeys = true },
            scheduler = scheduler,
        )
    }

    @After
    fun tearDown() = database.close()

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
    fun `saving asks for a sync`() = runTest(testDispatcher) {
        repository.saveEvent(draft())

        // The request carries a connectivity constraint, so asking while offline costs nothing and
        // is what makes the change leave the phone the moment signal returns — rather than sitting
        // until the next cold start.
        assertEquals(1, scheduler.requests)
    }

    @Test
    fun `a rejected draft asks for nothing`() = runTest(testDispatcher) {
        val result = repository.saveEvent(draft().copy(artistNames = listOf(" ")))

        assertTrue(result is DataResult.Failure)
        assertEquals(0, scheduler.requests)
    }

    @Test
    fun `every queued change asks, so a request is never missed`() = runTest(testDispatcher) {
        val eventId = (repository.saveEvent(draft()) as DataResult.Success).data
        repository.deleteEvent(eventId)

        // Superseding trims the *queue*, but each change still asks: an earlier request may
        // already have run and drained, leaving the later change with nothing scheduled.
        assertEquals(2, scheduler.requests)
    }
}
