package dev.abhinav.artistpin.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.auth.AuthState
import dev.abhinav.artistpin.core.database.ArtistEntity
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.ArtistProfile
import dev.abhinav.artistpin.core.model.BackupArtist
import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.sync.LibrarySync
import dev.abhinav.artistpin.data.sync.SyncScheduler
import dev.abhinav.artistpin.data.sync.SetArtistProfilePayload
import dev.abhinav.artistpin.data.sync.SyncOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
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
import java.time.LocalDate

/**
 * Artwork resolved on this device has to reach the catalog, and a pull must not erase it first.
 *
 * Both halves of one bug: nothing ever called setArtistProfile, so the catalog kept a null image
 * for every artist added after the original migration — and the full pull, which rebuilds the
 * artist table from exactly that catalog, put the null back over the portrait a lookup had just
 * fetched. The artist then showed initials until the app was restarted, because the in-memory
 * "already attempted" guard refuses to look the same artist up twice in one session.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var database: ArtistPinDatabase
    private lateinit var backup: RoomBackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ArtistPinDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        backup = RoomBackupRepository(
            database = database,
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            json = json,
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    private fun payloadWith(artist: BackupArtist) = BackupData(artists = listOf(artist))

    @Test
    fun `a pull keeps artwork this device resolved but the server has not got yet`() =
        runTest(testDispatcher) {
            database.artistDao().upsertArtists(
                listOf(
                    ArtistEntity(
                        id = "local-id",
                        name = "Bedouin",
                        imageUrl = "https://img.example/bedouin.jpg",
                        genres = "melodic house",
                        spotifyUrl = "https://open.spotify.com/artist/bedouin",
                    ),
                ),
            )

            // What export_backup returns for an artist whose profile was never pushed up. Note
            // the different id: the catalog's, not the one Room generated locally.
            backup.restore(
                payloadWith(BackupArtist(id = "catalog-id", name = "Bedouin")),
            )

            val stored = database.artistDao().allArtists().single()
            assertEquals("catalog-id", stored.id)
            assertEquals("https://img.example/bedouin.jpg", stored.imageUrl)
            // Null genres is what re-queues an artist for lookup, so this is the difference
            // between looking Bedouin up once and looking them up after every single sync.
            assertEquals("melodic house", stored.genres)
            assertEquals("https://open.spotify.com/artist/bedouin", stored.spotifyUrl)
        }

    @Test
    fun `the payload still wins when it actually carries a portrait`() = runTest(testDispatcher) {
        database.artistDao().upsertArtists(
            listOf(ArtistEntity(id = "local-id", name = "Bedouin", imageUrl = "https://old.example/x.jpg")),
        )

        backup.restore(
            payloadWith(
                BackupArtist(
                    id = "catalog-id",
                    name = "Bedouin",
                    imageUrl = "https://img.example/new.jpg",
                    genres = "melodic house",
                ),
            ),
        )

        assertEquals("https://img.example/new.jpg", database.artistDao().allArtists().single().imageUrl)
    }

    @Test
    fun `an artist the payload does not mention is still removed`() = runTest(testDispatcher) {
        database.artistDao().upsertArtists(
            listOf(ArtistEntity(id = "gone", name = "Someone Deleted", imageUrl = "https://img.example/g.jpg")),
        )

        backup.restore(payloadWith(BackupArtist(id = "catalog-id", name = "Bedouin")))

        // Preserving artwork must not turn a replace into a merge: a deletion made on another
        // device still has to land.
        assertEquals(listOf("Bedouin"), database.artistDao().allArtists().map { it.name })
    }

    @Test
    fun `a resolved lookup is queued for the catalog`() = runTest(testDispatcher) {
        val repository = offlineFirstWith(
            FakeArtistImageSource(
                mapOf(
                    "Bedouin" to ArtistProfile(
                        imageUrl = "https://img.example/bedouin.jpg",
                        genres = listOf("melodic house"),
                        spotifyUrl = "https://open.spotify.com/artist/bedouin",
                    ),
                ),
            ),
        )
        repository.saveEvent(draft())

        val job = CoroutineScope(testDispatcher).launch {
            repository.keepArtistArtworkFresh()
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        val queued = database.syncOutboxDao().pending()
            .single { it.operation == SyncOperation.SET_ARTIST_PROFILE.name }
        val payload = json.decodeFromString(SetArtistProfilePayload.serializer(), queued.payloadJson)
        assertEquals("Bedouin", payload.artistName)
        assertEquals("https://img.example/bedouin.jpg", payload.imageUrl)
        assertEquals("melodic house", payload.genres)
    }

    @Test
    fun `draining that entry sends the artwork to the catalog under the name's id`() =
        runTest(testDispatcher) {
            val api = FakeBackendApi()
            val auth = FakeAuthRepository().apply { setState(AuthState.SignedIn("user-1", null)) }
            val repository = offlineFirstWith(
                FakeArtistImageSource(
                    mapOf("Bedouin" to ArtistProfile(imageUrl = "https://img.example/bedouin.jpg")),
                ),
            )
            repository.saveEvent(draft())
            val job = CoroutineScope(testDispatcher).launch {
                repository.keepArtistArtworkFresh()
            }
            testScheduler.advanceUntilIdle()
            job.cancel()

            val sync = LibrarySync(
                outbox = database.syncOutboxDao(),
                api = api,
                localBackup = backup,
                mediaDao = database.mediaDao(),
                auth = auth,
                json = json,
                ioDispatcher = testDispatcher,
                scheduler = object : SyncScheduler { override fun requestSync() = Unit },
            )
            sync.syncNow()

            val write = api.profileWrites.single()
            // Resolved by name, because the local id means nothing to the catalog.
            assertEquals("catalog-id-for-bedouin", write.artistId)
            assertEquals("https://img.example/bedouin.jpg", write.imageUrl)
            assertTrue(api.findOrCreateCalls.contains("Bedouin"))
        }

    private fun offlineFirstWith(images: ArtistImageSource): OfflineFirstConcertRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return OfflineFirstConcertRepository(
            local = RoomConcertRepository(
                concertDao = database.concertDao(),
                artistDao = database.artistDao(),
                mediaDao = database.mediaDao(),
                mediaImporter = MediaImporter(context, testDispatcher),
                artistImageSource = images,
                ioDispatcher = testDispatcher,
            ),
            outbox = database.syncOutboxDao(),
            mediaDao = database.mediaDao(),
            json = json,
        )
    }

    private fun draft() = EventDraft(
        date = LocalDate.of(2025, 6, 14),
        cityName = "Queens",
        country = "United States",
        venueName = "Knockdown Center",
        latitude = 40.71,
        longitude = -73.92,
        artistNames = listOf("Bedouin"),
    )
}
