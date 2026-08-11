package dev.abhinav.artistpin.feature.artists

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.preferences.SettingsStore
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.FakeArtistImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtistSortPersistenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository

    private class FakeSettings(initial: String? = null) : SettingsStore {
        val stored = MutableStateFlow(initial)
        override val artistSort: Flow<String?> = stored
        override suspend fun setArtistSort(value: String) {
            stored.value = value
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
        repository = ConcertRepository(
            concertDao = database.concertDao(),
            artistDao = database.artistDao(),
            mediaDao = database.mediaDao(),
            mediaImporter = MediaImporter(context, testDispatcher),
            artistImageSource = FakeArtistImageSource(),
            ioDispatcher = testDispatcher,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun `the stored ordering is restored on launch`() = runTest(testDispatcher) {
        val viewModel = ArtistsViewModel(repository, FakeSettings(ArtistSort.CITY.name))
        advanceUntilIdle()

        assertEquals(ArtistSort.CITY, viewModel.uiState.value.sort)
    }

    @Test
    fun `choosing an ordering stores it`() = runTest(testDispatcher) {
        val settings = FakeSettings()
        val viewModel = ArtistsViewModel(repository, settings)
        advanceUntilIdle()

        viewModel.onSortSelected(ArtistSort.RECENTLY_SEEN)
        advanceUntilIdle()

        assertEquals(ArtistSort.RECENTLY_SEEN.name, settings.stored.value)
        assertEquals(ArtistSort.RECENTLY_SEEN, viewModel.uiState.value.sort)
    }

    @Test
    fun `nothing stored yet leaves the default in place`() = runTest(testDispatcher) {
        val viewModel = ArtistsViewModel(repository, FakeSettings(null))
        advanceUntilIdle()

        assertEquals(ArtistSort.MOST_SEEN, viewModel.uiState.value.sort)
    }

    /** A name from an older build, or a corrupt file, must not leave the list unsorted. */
    @Test
    fun `an unrecognized stored name falls back to the default`() = runTest(testDispatcher) {
        val viewModel = ArtistsViewModel(repository, FakeSettings("SORTED_BY_VIBES"))
        advanceUntilIdle()

        assertEquals(ArtistSort.MOST_SEEN, viewModel.uiState.value.sort)
    }
}
