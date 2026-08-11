package dev.abhinav.artistpin.feature.eventedit

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.data.ArtistSuggestion
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.EventDraft
import dev.abhinav.artistpin.data.FakeArtistImageSource
import dev.abhinav.artistpin.data.FakeVenueSearchService
import dev.abhinav.artistpin.data.ImportedArtist
import dev.abhinav.artistpin.data.ImportedEvent
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.RemoteArtistSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
import java.time.LocalDate

/** The Who and When halves: roles, the typeahead, the date chips and what the button says. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EventEditFormTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository
    private lateinit var venues: FakeVenueSearchService

    private class NoLinks : EventLinkImporter {
        override fun canImport(url: String) = false
        override suspend fun import(url: String) =
            DataResult.Failure(dev.abhinav.artistpin.core.model.DataError.Validation("no"))
    }

    private class FakeArtistSearch(var results: List<ArtistSuggestion> = emptyList()) : RemoteArtistSearch {
        var calls = 0
        var lastQuery: String? = null
        override suspend fun search(query: String): List<ArtistSuggestion> {
            calls++
            lastQuery = query
            return results
        }
    }

    private val artistSearch = FakeArtistSearch()

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
        venues = FakeVenueSearchService()
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private fun joshBaker(venueName: String = "Knockdown Center") = ImportedEvent(
        title = "JOSH BAKER: DAY AND NIGHT",
        date = LocalDate.of(2026, 8, 8),
        artists = listOf(
            ImportedArtist("Josh Baker", "https://img/josh.jpg"),
            ImportedArtist("Laidlaw", null),
        ),
        venueName = venueName,
        city = "New York",
        region = "NY",
        country = "United States of America",
        latitude = 40.71496,
        longitude = -73.91348,
        address = "52-19 Flushing Ave, Maspeth, NY 11378, USA",
        source = "DICE",
    )

    private class OneLink(private val event: ImportedEvent) : EventLinkImporter {
        override fun canImport(url: String) = true
        override suspend fun import(url: String) = DataResult.Success(event)
    }

    private fun viewModel(links: EventLinkImporter = NoLinks()) = EventEditViewModel(
        savedStateHandle = SavedStateHandle(),
        repository = repository,
        venueSearch = venues,
        artistSearch = artistSearch,
        linkImporter = links,
    )

    private fun EventEditViewModel.add(name: String) {
        onArtistQueryChange(name)
        onAddTypedArtist()
    }

    /** Takes the scope explicitly so it can advance the shared test scheduler. */
    private fun TestScope.pickVenue(vm: EventEditViewModel) {
        vm.onVenueQueryChange("Lincoln")
        advanceUntilIdle()
        vm.onPlaceSuggestionPicked(vm.uiState.value.venueResults.single())
        advanceUntilIdle()
    }

    // ---- Roles -------------------------------------------------------------------------

    @Test
    fun `the first artist headlines and the rest support`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.add("Boris Brejcha")
        vm.add("Brina Knauss")

        assertEquals(listOf("Boris Brejcha"), vm.uiState.value.headliners)
        assertEquals(listOf("Brina Knauss"), vm.uiState.value.support)
    }

    @Test
    fun `tapping a name switches its role`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")
        vm.add("Brina Knauss")

        vm.onToggleArtistRole("Brina Knauss")

        assertEquals(listOf("Boris Brejcha", "Brina Knauss"), vm.uiState.value.headliners)
        assertTrue(vm.uiState.value.support.isEmpty())
    }

    /** A bill of nothing but support acts would leave the show with no name to fall back on. */
    @Test
    fun `removing the headliner promotes whoever is left`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")
        vm.add("Brina Knauss")
        vm.add("Miss Monique")

        vm.onRemoveArtist("Boris Brejcha")

        assertEquals(listOf("Brina Knauss"), vm.uiState.value.headliners)
        assertEquals(listOf("Miss Monique"), vm.uiState.value.support)
    }

    @Test
    fun `the same artist cannot be added twice`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.add("Bedouin")
        vm.add("bedouin")

        assertEquals(1, vm.uiState.value.artists.size)
    }

    // ---- Typeahead ---------------------------------------------------------------------

    @Test
    fun `artists already on the bill drop out of the results`() = runTest(testDispatcher) {
        artistSearch.results = listOf(ArtistSuggestion("Boris Brejcha"), ArtistSuggestion("Boris Way"))
        val vm = viewModel()

        vm.add("Boris Brejcha")
        vm.onArtistQueryChange("boris")
        advanceUntilIdle()

        assertEquals(listOf("Boris Way"), vm.uiState.value.artistSuggestions.map { it.name })
    }

    /** Someone you've actually seen beats a stranger with a similar name. */
    @Test
    fun `artists from your library come first`() = runTest(testDispatcher) {
        repository.saveEvent(
            EventDraft(
                date = LocalDate.of(2025, 3, 1),
                cityName = "Berlin",
                country = "Germany",
                venueName = "Berghain",
                latitude = 52.5111,
                longitude = 13.4432,
                artistNames = listOf("Boris Brejcha"),
            ),
        )
        advanceUntilIdle()
        artistSearch.results = listOf(ArtistSuggestion("Boris Way"), ArtistSuggestion("Boris Brejcha"))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onArtistQueryChange("boris")
        advanceUntilIdle()

        val suggestions = vm.uiState.value.artistSuggestions
        assertEquals(listOf("Boris Brejcha", "Boris Way"), suggestions.map { it.name })
        assertEquals("the library entry carries its count", 1, suggestions.first().timesSeen)
    }

    @Test
    fun `a one-character query never reaches the network`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onArtistQueryChange("b")
        advanceUntilIdle()

        assertEquals(0, artistSearch.calls)
    }

    /** An act no provider has heard of still has to be addable. */
    @Test
    fun `a query matching nothing offers to add it as typed`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onArtistQueryChange("Some Local Opener")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.canAddTypedArtist)
        vm.onAddTypedArtist()
        assertEquals(listOf("Some Local Opener"), vm.uiState.value.artists.map { it.name })
    }

    @Test
    fun `an exact match is not offered as a new artist`() = runTest(testDispatcher) {
        artistSearch.results = listOf(ArtistSuggestion("Bedouin"))
        val vm = viewModel()

        vm.onArtistQueryChange("Bedouin")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canAddTypedArtist)
    }

    // ---- When --------------------------------------------------------------------------

    @Test
    fun `the date chips resolve against today`() = runTest(testDispatcher) {
        val vm = viewModel()

        assertEquals(LocalDate.now(), vm.uiState.value.date)

        vm.onWhenChosen(WhenChoice.LAST_NIGHT)
        assertEquals(LocalDate.now().minusDays(1), vm.uiState.value.date)

        vm.onDateSelected(LocalDate.of(2024, 6, 9))
        assertEquals(LocalDate.of(2024, 6, 9), vm.uiState.value.date)
        assertEquals(WhenChoice.CUSTOM, vm.uiState.value.whenChoice)
    }

    /**
     * Regression: the chips used to resolve against today at save time, so a form left open past
     * midnight — or an edit of a show dated today — silently moved the show.
     */
    @Test
    fun `a chip fixes its date when tapped, not when saved`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onWhenChosen(WhenChoice.LAST_NIGHT)
        val chosen = vm.uiState.value.date

        assertEquals(LocalDate.now().minusDays(1), chosen)
        // Nothing else touching the form may move it.
        vm.add("Boris Brejcha")
        pickVenue(vm)
        assertEquals(chosen, vm.uiState.value.date)
    }

    @Test
    fun `the pick chip opens the date picker rather than selecting a date`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onWhenChosen(WhenChoice.CUSTOM)

        assertTrue(vm.uiState.value.showDatePicker)
        assertEquals(WhenChoice.TONIGHT, vm.uiState.value.whenChoice)
    }

    // ---- The button --------------------------------------------------------------------

    @Test
    fun `the button names what is missing rather than just sitting disabled`() = runTest(testDispatcher) {
        val vm = viewModel()
        assertEquals("Add an artist to continue", vm.uiState.value.actionLabel)
        assertFalse(vm.uiState.value.isValid)

        vm.add("Boris Brejcha")
        assertEquals("Pick a venue to continue", vm.uiState.value.actionLabel)
        assertFalse(vm.uiState.value.isValid)

        pickVenue(vm)
        assertEquals("Add this show", vm.uiState.value.actionLabel)
        assertTrue(vm.uiState.value.isValid)
    }

    @Test
    fun `saving writes the bill with its roles intact`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")
        vm.add("Brina Knauss")
        pickVenue(vm)

        vm.onSave()
        advanceUntilIdle()

        val event = repository.observeAllEvents().first().single()
        assertEquals("Boris Brejcha", event.displayTitle)
        assertEquals(listOf("Boris Brejcha", "Brina Knauss"), event.artistNames)
    }

    @Test
    fun `a festival name overrides the headliner as the show title`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")
        pickVenue(vm)
        vm.onTitleChange("Resistance MMW")

        vm.onSave()
        advanceUntilIdle()

        assertEquals("Resistance MMW", repository.observeAllEvents().first().single().displayTitle)
    }

    // ---- Import from a link -------------------------------------------------------------

    @Test
    fun `an imported event fills the form and leaves it editable`() = runTest(testDispatcher) {
        val vm = viewModel(links = OneLink(joshBaker()))

        vm.onLinkQueryChange("https://dice.fm/event/6dlgq3-josh-baker")
        vm.onImportLink()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("Josh Baker"), state.headliners)
        assertEquals(listOf("Laidlaw"), state.support)
        assertEquals(LocalDate.of(2026, 8, 8), state.date)
        assertEquals("JOSH BAKER: DAY AND NIGHT", state.title)
        // The venue is whatever Places says it is, not what the ticket page claimed.
        assertEquals("Lincoln Financial Field", state.venue?.name)
        assertEquals("Philadelphia", state.venue?.city)
        assertTrue("an imported show is still just a filled-in form", state.isValid)
        assertEquals("the link field clears once it has been used", "", state.linkQuery)

        // ...and every part of it can still be changed.
        vm.onToggleArtistRole("Laidlaw")
        assertEquals(listOf("Josh Baker", "Laidlaw"), vm.uiState.value.headliners)
    }

    /**
     * The discrepancy this guards: DICE files Knockdown Center under "New York" while Places
     * calls it Queens. Trusting the ticket page would split one city into two on the map.
     */
    @Test
    fun `the venue is looked up by name rather than taken from the ticket page`() =
        runTest(testDispatcher) {
            val vm = viewModel(links = OneLink(joshBaker()))

            vm.onLinkQueryChange("https://link.dice.fm/Q6u3ZROdl5b")
            vm.onImportLink()
            advanceUntilIdle()

            // The ticket page's own city is only ever a search hint.
            assertEquals("Knockdown Center, New York", venues.lastQuery)
            val venue = vm.uiState.value.venue!!
            assertEquals("Philadelphia", venue.city)
            assertEquals("PA", venue.region)
            assertEquals(39.9008, venue.latitude, 0.0001)
        }

    /** A venue Places has never heard of has to be picked, not assumed. */
    @Test
    fun `an unresolvable venue is left in the search field`() = runTest(testDispatcher) {
        venues.results = emptyList()
        val vm = viewModel(links = OneLink(joshBaker(venueName = "Somewhere Unlisted")))

        vm.onLinkQueryChange("https://dice.fm/event/abc")
        vm.onImportLink()
        advanceUntilIdle()

        assertNull(vm.uiState.value.venue)
        assertEquals("Somewhere Unlisted", vm.uiState.value.venueQuery)
        assertEquals("Pick a venue to continue", vm.uiState.value.actionLabel)
    }

    @Test
    fun `a link that cannot be read leaves the form untouched`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")

        vm.onLinkQueryChange("https://example.com/whatever")
        vm.onImportLink()
        advanceUntilIdle()

        assertEquals(listOf("Boris Brejcha"), vm.uiState.value.artists.map { it.name })
        assertNull(vm.uiState.value.venue)
    }

    // ---- Leaving -----------------------------------------------------------------------

    /** Dropping the venue is exactly the kind of change that must not vanish silently. */
    @Test
    fun `clearing the venue counts as something to lose`() = runTest(testDispatcher) {
        val vm = viewModel()
        pickVenue(vm)
        vm.onClearVenue()

        vm.onBackRequested()

        assertTrue(vm.uiState.value.showDiscardPrompt)
    }

    @Test
    fun `backing out of an untouched form does not ask`() = runTest(testDispatcher) {
        val vm = viewModel()

        vm.onBackRequested()

        assertFalse(vm.uiState.value.showDiscardPrompt)
        assertEquals(EventEditEffect.NavigateBack, vm.effects.first())
    }

    @Test
    fun `backing out after entering something asks first`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")

        vm.onBackRequested()

        assertTrue(vm.uiState.value.showDiscardPrompt)
    }

    /** Typing into a box and walking away is still work worth not losing silently. */
    @Test
    fun `a half-typed search counts as something to lose`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.onArtistQueryChange("bor")

        vm.onBackRequested()

        assertTrue(vm.uiState.value.showDiscardPrompt)
    }

    @Test
    fun `discarding leaves the screen`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.add("Boris Brejcha")
        vm.onBackRequested()

        vm.onDiscardConfirmed()

        assertFalse(vm.uiState.value.showDiscardPrompt)
        assertEquals(EventEditEffect.NavigateBack, vm.effects.first())
    }
}
