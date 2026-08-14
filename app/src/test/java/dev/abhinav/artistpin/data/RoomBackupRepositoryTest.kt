package dev.abhinav.artistpin.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.Billing
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.getOrNull
import kotlinx.coroutines.flow.first
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RoomBackupRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var concerts: ConcertRepository
    private lateinit var backups: RoomBackupRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ArtistPinDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(testDispatcher.asExecutor())
            .setTransactionExecutor(testDispatcher.asExecutor())
            .build()
        concerts = RoomConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = FakeArtistImageSource(),
            ioDispatcher = testDispatcher,
        )
        backups = RoomBackupRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            json = Json { ignoreUnknownKeys = true },
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun seedTwoShows() {
        concerts.saveEvent(
            EventDraft(
                date = LocalDate.of(2024, 7, 12),
                cityName = "Toronto",
                country = "Canada",
                venueName = "History",
                latitude = 43.6532,
                longitude = -79.3832,
                artistNames = listOf("The Weeknd"),
                supportArtistNames = listOf("Mike Dean"),
            ),
        )
        concerts.saveEvent(
            EventDraft(
                date = LocalDate.of(2025, 3, 1),
                cityName = "Berlin",
                country = "Germany",
                venueName = "Berghain",
                latitude = 52.5111,
                longitude = 13.4432,
                artistNames = listOf("Fred again.."),
            ),
        )
    }

    @Test
    fun `a backup round-trips every show, artist, venue and city`() = runTest(testDispatcher) {
        seedTwoShows()
        val before = concerts.observeAllEvents().first()

        val exported = (backups.export() as DataResult.Success).data
        // Wipe everything, then restore from the file alone.
        backups.restore(BackupData())
        assertTrue(concerts.observeAllEvents().first().isEmpty())

        val parsed = (backups.parse(exported) as DataResult.Success).data
        backups.restore(parsed)

        val after = concerts.observeAllEvents().first()
        assertEquals(before.map { it.id }.toSet(), after.map { it.id }.toSet())
        assertEquals(before.map { it.displayTitle }.toSet(), after.map { it.displayTitle }.toSet())
        assertEquals(2, concerts.observeCityPins().first().size)
    }

    /** Billing has to survive, otherwise support acts come back as headliners. */
    @Test
    fun `support acts keep their billing across a restore`() = runTest(testDispatcher) {
        seedTwoShows()
        val exported = (backups.export() as DataResult.Success).data

        backups.restore((backups.parse(exported) as DataResult.Success).data)

        val event = concerts.observeAllEvents().first().single { it.cityName == "Toronto" }
        val details = concerts.observeEvent(event.id).first()!!
        assertEquals(listOf("The Weeknd"), details.headliners.map { it.name })
        assertEquals(
            Billing.SUPPORT,
            details.artists.single { it.artist.name == "Mike Dean" }.billing,
        )
    }

    @Test
    fun `restore replaces rather than merges`() = runTest(testDispatcher) {
        seedTwoShows()
        val exported = (backups.export() as DataResult.Success).data
        val parsed = (backups.parse(exported) as DataResult.Success).data

        // A show that is not in the backup must not survive the restore.
        concerts.saveEvent(
            EventDraft(
                date = LocalDate.of(2026, 1, 1),
                cityName = "Lisbon",
                country = "Portugal",
                venueName = "Lux",
                latitude = 38.71,
                longitude = -9.12,
                artistNames = listOf("Nobody"),
            ),
        )
        assertEquals(3, concerts.observeAllEvents().first().size)

        backups.restore(parsed)

        val cities = concerts.observeCityPins().first().map { it.city.name }
        assertEquals(2, concerts.observeAllEvents().first().size)
        assertTrue("Lisbon should be gone", "Lisbon" !in cities)
        assertTrue("Nobody should be gone", concerts.observeArtistSummaries().first().none { it.artist.name == "Nobody" })
    }

    @Test
    fun `a file that is not a backup fails as validation rather than crashing`() = runTest(testDispatcher) {
        val result = backups.parse("not json at all")

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).error is DataError.Validation)
    }

    /** A file from a future version could reference tables this build cannot populate. */
    @Test
    fun `a newer backup version is refused`() = runTest(testDispatcher) {
        val future = """{"version":99,"exportedAtEpochMillis":0}"""

        val result = backups.parse(future)

        assertTrue(result is DataResult.Failure)
        assertTrue((result as DataResult.Failure).error is DataError.Validation)
    }

    @Test
    fun `exporting an empty library produces a restorable file`() = runTest(testDispatcher) {
        val exported = (backups.export() as DataResult.Success).data
        val parsed = backups.parse(exported).getOrNull()!!

        assertEquals(0, parsed.summary.shows)
        assertTrue(backups.restore(parsed) is DataResult.Success)
    }

    /** Photo files are not in the backup, so rows pointing at missing files must be dropped. */
    @Test
    fun `media rows whose files are gone are not restored`() = runTest(testDispatcher) {
        seedTwoShows()
        val eventId = concerts.observeAllEvents().first().first().id
        database.mediaDao().upsertMedia(
            listOf(
                dev.abhinav.artistpin.core.database.EventMediaEntity(
                    id = "m1",
                    eventId = eventId,
                    localPath = "/does/not/exist.jpg",
                    originalUri = "content://picker/m1",
                    mimeType = "image/jpeg",
                ),
            ),
        )

        val exported = (backups.export() as DataResult.Success).data
        backups.restore((backups.parse(exported) as DataResult.Success).data)

        assertTrue(database.mediaDao().getMediaForEvent(eventId).isEmpty())
    }

    @Test
    fun `restore replaces the local library rather than merging`() {
        // The opposite of BackendBackupRepository, deliberately: on-device restore wipes and
        // rewrites the tables. The confirmation dialog reads this flag to decide whether to warn
        // about deletion, so the two implementations disagreeing here is the point.
        assertTrue(backups.restoreReplaces)
    }
}
