package dev.abhinav.artistpin.feature.eventedit

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.data.ArtistSuggestion
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.RoomConcertRepository
import dev.abhinav.artistpin.data.FakeArtistImageSource
import dev.abhinav.artistpin.data.FakeVenueSearchService
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.RemoteArtistSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Where half of the form: search is debounced, one tap fills everything, failures degrade. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventEditViewModelSearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository
    private lateinit var search: FakeVenueSearchService

    private class NoLinks : EventLinkImporter {
        override fun canImport(url: String) = false
        override suspend fun import(url: String) =
            DataResult.Failure(dev.abhinav.artistpin.core.model.DataError.Validation("no"))
    }

    private class NoArtists : RemoteArtistSearch {
        override suspend fun search(query: String): List<ArtistSuggestion> = emptyList()
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
        search = FakeVenueSearchService()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = EventEditViewModel(
        savedStateHandle = SavedStateHandle(),
        repository = repository,
        venueSearch = search,
        artistSearch = NoArtists(),
        linkImporter = NoLinks(),
    )

    @Test
    fun `a burst of keystrokes costs a single lookup`() = runTest(testDispatcher) {
        val vm = viewModel()

        "Lincoln".forEachIndexed { index, _ -> vm.onVenueQueryChange("Lincoln".take(index + 1)) }
        advanceUntilIdle()

        assertEquals(1, search.searchCallCount)
        assertEquals("Lincoln", search.lastQuery)
    }

    @Test
    fun `a query shorter than three characters never reaches the network`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onVenueQueryChange("Li")
        advanceUntilIdle()

        assertEquals(0, search.searchCallCount)
        assertTrue(vm.uiState.value.venueResults.isEmpty())
    }

    @Test
    fun `clearing the field cancels a search that hasn't fired yet`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onVenueQueryChange("Lincoln")
        advanceTimeBy(100)
        vm.onVenueQueryChange("")
        advanceUntilIdle()

        assertEquals(0, search.searchCallCount)
    }

    /** One tap has to fill city, state, country and the pin — they are no longer fields. */
    @Test
    fun `picking a result fills the whole location and satisfies validation`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onAddTypedArtistNamed("Fred again..")
        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()

        vm.onPlaceSuggestionPicked(vm.uiState.value.venueResults.single())
        advanceUntilIdle()

        val venue = vm.uiState.value.venue!!
        assertEquals("Lincoln Financial Field", venue.name)
        assertEquals("Philadelphia", venue.city)
        assertEquals("PA", venue.region)
        assertEquals("United States", venue.country)
        assertEquals(39.9008, venue.latitude, 0.0001)
        assertEquals(-75.1675, venue.longitude, 0.0001)
        assertTrue(vm.uiState.value.isValid)
        assertTrue(vm.uiState.value.venueResults.isEmpty())
    }

    @Test
    fun `search stays off entirely when no Places key is configured`() = runTest(testDispatcher) {
        search.isAvailable = false
        val vm = viewModel()

        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.venueSearchEnabled)
        assertEquals(0, search.searchCallCount)
    }

    @Test
    fun `a failed lookup clears results and leaves the query alone`() = runTest(testDispatcher) {
        search.searchError = DataError.Network("offline")
        val vm = viewModel()

        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.venueResults.isEmpty())
        assertFalse(state.isSearchingVenues)
        assertEquals("Lincoln", state.venueQuery)
    }

    /** Change puts you back to an empty search field rather than a half-filled card. */
    @Test
    fun `changing the venue clears the selection and its address disclosure`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()
        vm.onPlaceSuggestionPicked(vm.uiState.value.venueResults.single())
        advanceUntilIdle()
        vm.onToggleAddress()

        vm.onClearVenue()

        assertNull(vm.uiState.value.venue)
        assertFalse(vm.uiState.value.addressExpanded)
        assertEquals("", vm.uiState.value.venueQuery)
    }

    /** A hand-corrected city must survive to the saved show, not be overwritten by the geocode. */
    @Test
    fun `editing the city detaches it from the geocoded one`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()
        vm.onPlaceSuggestionPicked(vm.uiState.value.venueResults.single())
        advanceUntilIdle()

        vm.onCityChange("Philly")

        assertEquals("Philly", vm.uiState.value.venue?.city)
        assertNull(vm.uiState.value.venue?.existingCityId)
    }
}

/** Adds an artist by name the way the typed-name row does, without going through the query field. */
private fun EventEditViewModel.onAddTypedArtistNamed(name: String) {
    onArtistQueryChange(name)
    onAddTypedArtist()
}
