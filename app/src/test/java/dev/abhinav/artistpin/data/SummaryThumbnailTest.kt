package dev.abhinav.artistpin.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.EventMediaEntity
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * A list row has to be able to find its thumbnail on a device that never held the file.
 *
 * This was the gap behind "the show screen has photos but the artist screen doesn't": the summary
 * query returned only the local path, which on a second phone points at the first phone's storage.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SummaryThumbnailTest {

    private val testDispatcher = StandardTestDispatcher()
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
        repository = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = FakeArtistImageSource(),
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun savedEventId(): String {
        val saved = repository.saveEvent(
            EventDraft(
                date = LocalDate.of(2025, 6, 14),
                cityName = "Queens",
                country = "United States",
                venueName = "Knockdown Center",
                latitude = 40.71,
                longitude = -73.92,
                artistNames = listOf("Bedouin"),
            ),
        )
        return (saved as DataResult.Success).data
    }

    private suspend fun addVideo(eventId: String, sortIndex: Int) {
        database.mediaDao().upsertMedia(
            listOf(
                EventMediaEntity(
                    id = "video-$sortIndex",
                    eventId = eventId,
                    localPath = "/data/first-phone/video-$sortIndex.mp4",
                    originalUri = "content://media/v$sortIndex",
                    mimeType = "video/mp4",
                    sortIndex = sortIndex,
                    // Videos never upload, so this is the only state one can be in.
                    remotePath = null,
                ),
            ),
        )
    }

    private suspend fun addPhoto(eventId: String, sortIndex: Int, remotePath: String?) {
        database.mediaDao().upsertMedia(
            listOf(
                EventMediaEntity(
                    id = "media-$sortIndex",
                    eventId = eventId,
                    localPath = "/data/first-phone/media-$sortIndex.jpg",
                    originalUri = "content://media/$sortIndex",
                    mimeType = "image/jpeg",
                    sortIndex = sortIndex,
                    remotePath = remotePath,
                ),
            ),
        )
    }

    @Test
    fun `a summary carries the storage path of its thumbnail`() = runTest(testDispatcher) {
        val eventId = savedEventId()
        addPhoto(eventId, sortIndex = 0, remotePath = "user-1/$eventId/media-0.jpg")

        val summary = repository.observeAllEvents().first().single()

        // Without this the artist and city lists have only a path to another phone's storage, and
        // render nothing while the show screen — which reads the full media rows — is fine.
        assertEquals("user-1/$eventId/media-0.jpg", summary.thumbnailRemotePath)
    }

    @Test
    fun `the storage path belongs to the same photo the thumbnail does`() = runTest(testDispatcher) {
        val eventId = savedEventId()
        // Deliberately inserted out of order: the thumbnail is the lowest sortIndex, and its
        // storage path has to come from that same row rather than whichever the database returns
        // first.
        addPhoto(eventId, sortIndex = 1, remotePath = "user-1/$eventId/media-1.jpg")
        addPhoto(eventId, sortIndex = 0, remotePath = "user-1/$eventId/media-0.jpg")

        val summary = repository.observeAllEvents().first().single()

        assertEquals("/data/first-phone/media-0.jpg", summary.thumbnailPath)
        assertEquals("user-1/$eventId/media-0.jpg", summary.thumbnailRemotePath)
    }

    @Test
    fun `a photo still waiting to upload has no storage path`() = runTest(testDispatcher) {
        val eventId = savedEventId()
        addPhoto(eventId, sortIndex = 0, remotePath = null)

        val summary = repository.observeAllEvents().first().single()

        assertNull(summary.thumbnailRemotePath)
        assertEquals("/data/first-phone/media-0.jpg", summary.thumbnailPath)
    }

    @Test
    fun `a reel that opens with a video still gets a photo thumbnail`() = runTest(testDispatcher) {
        val eventId = savedEventId()
        addVideo(eventId, sortIndex = 0)
        addPhoto(eventId, sortIndex = 1, remotePath = "user-1/$eventId/media-1.jpg")

        val summary = repository.observeAllEvents().first().single()

        // The video comes first in the reel but has no storage path and never will, so choosing
        // it would leave every other device with a blank square in the lists while the show screen
        // displays the photo fine.
        assertEquals("/data/first-phone/media-1.jpg", summary.thumbnailPath)
        assertEquals("user-1/$eventId/media-1.jpg", summary.thumbnailRemotePath)
    }

    @Test
    fun `a show with only videos still has a local thumbnail`() = runTest(testDispatcher) {
        val eventId = savedEventId()
        addVideo(eventId, sortIndex = 0)

        val summary = repository.observeAllEvents().first().single()

        // The phone that recorded it can render a frame, so the preference must not discard it.
        assertEquals("/data/first-phone/video-0.mp4", summary.thumbnailPath)
        assertNull(summary.thumbnailRemotePath)
    }
}
