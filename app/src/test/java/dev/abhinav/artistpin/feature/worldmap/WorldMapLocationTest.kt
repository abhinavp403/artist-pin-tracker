package dev.abhinav.artistpin.feature.worldmap

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.location.DeviceLocationProvider
import dev.abhinav.artistpin.core.media.BackupFileStore
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.Coordinates
import dev.abhinav.artistpin.data.RoomBackupRepository
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.RoomConcertRepository
import dev.abhinav.artistpin.data.EventDraft
import dev.abhinav.artistpin.data.FakeArtistImageSource
import dev.abhinav.artistpin.data.FakeBackendApi
import dev.abhinav.artistpin.data.LibraryMigrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WorldMapLocationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository
    private lateinit var backups: RoomBackupRepository
    private lateinit var fileStore: BackupFileStore

    private class FakeLocationProvider(var location: Coordinates?) : DeviceLocationProvider {
        var calls = 0
        override suspend fun currentLocation(): Coordinates? {
            calls++
            return location
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
        backups = RoomBackupRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            json = Json { ignoreUnknownKeys = true },
            ioDispatcher = testDispatcher,
        )
        fileStore = BackupFileStore(context, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    private fun viewModel(provider: DeviceLocationProvider) =
        WorldMapViewModel(
            repository,
            backups,
            fileStore,
            provider,
            LibraryMigrator(backups, FakeBackendApi(), Json, testDispatcher),
        )

    /**
     * Opening a venue in the dock replaced the map with the city screen. It now unfolds the
     * shows in place, which only works if the view model actually loads them.
     */
    @Test
    fun `expanding a venue loads its shows and collapsing clears them`() = runTest(testDispatcher) {
        repository.saveEvent(
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
        advanceUntilIdle()
        val viewModel = viewModel(FakeLocationProvider(null))
        advanceUntilIdle()
        val venueId = viewModel.uiState.value.venuePins.single().venue.id

        viewModel.onVenueToggled(venueId)
        advanceUntilIdle()

        assertEquals(venueId, viewModel.uiState.value.expandedVenueId)
        assertEquals(listOf("Fred again.."), viewModel.uiState.value.venueEvents.map { it.displayTitle })

        viewModel.onVenueToggled(venueId)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.expandedVenueId)
        assertTrue(viewModel.uiState.value.venueEvents.isEmpty())
    }

    /** Reopening a city with a venue still unfolded from last time is nobody's idea of fresh. */
    @Test
    fun `collapsing the city closes whatever venue was open`() = runTest(testDispatcher) {
        repository.saveEvent(
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
        advanceUntilIdle()
        val viewModel = viewModel(FakeLocationProvider(null))
        advanceUntilIdle()
        viewModel.onVenueToggled(viewModel.uiState.value.venuePins.single().venue.id)
        advanceUntilIdle()

        viewModel.onVenuesCollapsed()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.expandedVenueId)
        assertTrue(viewModel.uiState.value.venueEvents.isEmpty())
    }

    @Test
    fun `granting the permission resolves the opening camera to the device location`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeLocationProvider(Coordinates(25.7617, -80.1918)))

        viewModel.onLocationPermissionResult(granted = true)
        advanceUntilIdle()

        assertEquals(Coordinates(25.7617, -80.1918), viewModel.uiState.value.startLocation)
        assertFalse(viewModel.uiState.value.isLocatingUser)
    }

    @Test
    fun `denying the permission settles the camera question without a location`() = runTest(testDispatcher) {
        val provider = FakeLocationProvider(Coordinates(25.7617, -80.1918))
        val viewModel = viewModel(provider)

        viewModel.onLocationPermissionResult(granted = false)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.startLocation)
        assertFalse(viewModel.uiState.value.isLocatingUser)
        assertEquals("a denial must not reach the location provider", 0, provider.calls)
    }

    /** Location off, or no fix yet: the map has to stop waiting and fall back to fitting the pins. */
    @Test
    fun `a granted permission with no fix still settles the camera question`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeLocationProvider(location = null))

        viewModel.onLocationPermissionResult(granted = true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.startLocation)
        assertFalse(viewModel.uiState.value.isLocatingUser)
    }

    @Test
    fun `the map waits for the permission answer before deciding where to open`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeLocationProvider(Coordinates(1.0, 2.0)))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isLocatingUser)
    }

    /** A recomposition re-reporting the permission must not re-open the camera under the user. */
    @Test
    fun `a repeated permission result is ignored once the question is settled`() = runTest(testDispatcher) {
        val provider = FakeLocationProvider(Coordinates(25.7617, -80.1918))
        val viewModel = viewModel(provider)

        viewModel.onLocationPermissionResult(granted = true)
        advanceUntilIdle()
        viewModel.onLocationPermissionResult(granted = true)
        advanceUntilIdle()

        assertEquals(1, provider.calls)
    }
}
