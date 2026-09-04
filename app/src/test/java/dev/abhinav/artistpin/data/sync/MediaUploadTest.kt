package dev.abhinav.artistpin.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.database.CityEntity
import dev.abhinav.artistpin.core.database.EventEntity
import dev.abhinav.artistpin.core.database.EventMediaEntity
import dev.abhinav.artistpin.core.database.VenueEntity
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The upload half of Milestone D: photos whose bytes have never left the phone get sent, once, and
 * the fact that they were sent is recorded on both sides.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MediaUploadTest {

    /** A path no file lives at, so the restore has to keep the row on the strength of storage. */
    private val MISSING_PATH = "/data/gone/media-1.jpg"

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeBackendApi()

    private lateinit var database: ArtistPinDatabase
    private lateinit var sync: LibrarySync
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
            auth = FakeAuthRepository(userId = "user-1"),
            json = json,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    /** event_media has a foreign key to events, so the show has to exist before its photos do. */
    private suspend fun givenEvent(eventId: String) {
        database.concertDao().insertCities(
            listOf(CityEntity(id = "city-1", name = "Queens", country = "United States")),
        )
        database.concertDao().insertVenues(
            listOf(
                VenueEntity(
                    id = "venue-1",
                    name = "Knockdown Center",
                    cityId = "city-1",
                    latitude = 40.71,
                    longitude = -73.92,
                ),
            ),
        )
        database.concertDao().insertEvents(
            listOf(EventEntity(id = eventId, venueId = "venue-1", dateEpochDay = 20253)),
        )
    }

    /** A real file on disk, because the upload reads bytes rather than trusting the row. */
    private suspend fun givenPhoto(
        id: String = "media-1",
        eventId: String = "event-1",
        remotePath: String? = null,
    ): File {
        givenEvent(eventId)
        val file = File(context.filesDir, "$id.jpg").apply { writeText("pretend jpeg bytes") }
        database.mediaDao().upsertMedia(
            listOf(
                EventMediaEntity(
                    id = id,
                    eventId = eventId,
                    localPath = file.absolutePath,
                    originalUri = "content://media/$id",
                    mimeType = "image/jpeg",
                    sortIndex = 0,
                    remotePath = remotePath,
                ),
            ),
        )
        return file
    }

    /**
     * What `export_backup()` would return once the upload has been recorded. The pull that follows
     * every successful drain rebuilds Room from exactly this, so a payload that omitted
     * storagePath would silently un-mark every uploaded photo — the failure 0012 exists to prevent.
     */
    private fun serverState(storagePath: String?, localPath: String = MISSING_PATH) =
        serverStateJson(storagePath, localPath)

    private fun serverStateWithUploadedPhoto(storagePath: String) = serverStateJson(storagePath, MISSING_PATH)

    private fun serverStateJson(storagePath: String?, localPath: String) = json.parseToJsonElement(
        """
        {
          "version": 1,
          "cities": [{"id":"city-1","name":"Queens","country":"United States"}],
          "venues": [{"id":"venue-1","name":"Knockdown Center","cityId":"city-1",
                      "latitude":40.71,"longitude":-73.92}],
          "artists": [], "events": [{"id":"event-1","venueId":"venue-1","dateEpochDay":20253}],
          "eventArtists": [],
          "media": [{"id":"media-1","eventId":"event-1","localPath":"$localPath",
                     "storagePath":${storagePath?.let { "\"$it\"" } ?: "null"},
                     "originalUri":"content://media/media-1",
                     "mimeType":"image/jpeg","sortIndex":0}]
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `a photo with no remote copy is uploaded and recorded on both sides`() =
        runTest(testDispatcher) {
            val expectedPath = "user-1/event-1/media-1.jpg"
            givenPhoto()
            api.exportPayload = serverStateWithUploadedPhoto(expectedPath)

            sync.syncNow()

            // The path's first segment is what the storage policies read ownership from, so its
            // shape is load-bearing rather than cosmetic.
            assertEquals(listOf(expectedPath), api.uploads)
            assertEquals("media-1" to expectedPath, api.storagePathWrites.single())
        }

    @Test
    fun `an uploaded photo survives the refresh that follows the push`() = runTest(testDispatcher) {
        val expectedPath = "user-1/event-1/media-1.jpg"
        givenPhoto()
        api.exportPayload = serverStateWithUploadedPhoto(expectedPath)

        sync.syncNow()

        // The pull rebuilds Room wholesale. If storage_path did not survive that trip, the photo
        // would look un-uploaded again and be re-sent on the next sync, forever. Note the local
        // file is deliberately absent from the payload — the row still has to be kept, because the
        // bytes are now reachable from the bucket.
        assertEquals(expectedPath, database.mediaDao().media("media-1")?.remotePath)
    }

    @Test
    fun `an already-uploaded photo is left alone`() = runTest(testDispatcher) {
        givenPhoto(remotePath = "user-1/event-1/media-1.jpg")

        sync.syncNow()

        // Re-uploading every photo on every sync would burn the user's data allowance for nothing.
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun `a failed upload leaves the row un-uploaded rather than claiming success`() =
        runTest(testDispatcher) {
            givenPhoto()
            api.failUpload = FakeBackendApi.offline()

            sync.syncNow()

            // Recording the path before the bytes land would mark a photo as backed up when it is
            // not — the one lie this feature cannot afford, because it is exactly the case where
            // the user then wipes their phone.
            assertNull(database.mediaDao().media("media-1")?.remotePath)
            assertTrue(api.storagePathWrites.isEmpty())
        }

    @Test
    fun `a photo whose file has vanished is not re-queued on later syncs`() = runTest(testDispatcher) {
        val file = givenPhoto()
        api.exportPayload = serverState(storagePath = null, localPath = file.absolutePath)
        file.delete()

        sync.syncNow()
        // The second run is the whole point. The first sync always looks fine — the entry is
        // handled and the queue empties. Without a terminal state on the row, the backfill sees the
        // same photo again here, and on every sync after it, forever; and with twenty such rows
        // they would fill the batch and starve real photos out of the queue entirely.
        sync.syncNow()
        sync.syncNow()

        // The guarantee that matters: nothing was uploaded and the queue is not growing.
        assertTrue(api.uploads.isEmpty())
        assertEquals(0, database.syncOutboxDao().pendingCount())
    }

    @Test
    fun `an upload that keeps failing is eventually left alone`() = runTest(testDispatcher) {
        val file = givenPhoto()
        api.exportPayload = serverState(storagePath = null, localPath = file.absolutePath)
        api.failUpload = IllegalStateException("object rejected")

        // Each run gives up on the entry after LibrarySync.MAX_ATTEMPTS, and the row records that.
        repeat(LibrarySync.MAX_UPLOAD_ATTEMPTS * LibrarySync.MAX_ATTEMPTS + 2) { sync.syncNow() }

        // The row is retired rather than re-queued. Before the fix, the outbox's escape hatch was
        // useless here: the drain abandoned the entry and the backfill immediately recreated it.
        assertEquals(0, database.syncOutboxDao().pendingCount())
        assertNull(database.mediaDao().media("media-1")?.remotePath)
        assertTrue(
            (database.mediaDao().media("media-1")?.uploadAttempts ?: 0)
                >= LibrarySync.MAX_UPLOAD_ATTEMPTS,
        )
    }

    @Test
    fun `the same photo is not queued twice across runs`() = runTest(testDispatcher) {
        givenPhoto()
        api.failUpload = FakeBackendApi.offline()

        sync.syncNow()
        sync.syncNow()

        // queuePendingUploads runs every sync; without the already-queued check, a photo that
        // cannot upload would gain a duplicate entry on every attempt and the backlog would grow
        // while nothing progressed.
        assertEquals(1, database.syncOutboxDao().pendingCount())
    }
}
