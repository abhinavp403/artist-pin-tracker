package dev.abhinav.artistpin.feature.artists

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.abhinav.artistpin.core.database.ArtistPinDatabase
import dev.abhinav.artistpin.core.media.MediaImporter
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.EventDraft
import dev.abhinav.artistpin.data.FakeArtistImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArtistDetailRenameTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: ArtistPinDatabase
    private lateinit var repository: ConcertRepository

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

    private suspend fun seed(artist: String, date: LocalDate = LocalDate.of(2025, 3, 1)) {
        repository.saveEvent(
            EventDraft(
                date = date,
                cityName = "Berlin",
                country = "Germany",
                venueName = "Berghain",
                latitude = 52.5111,
                longitude = 13.4432,
                artistNames = listOf(artist),
            ),
        )
    }

    private suspend fun viewModelFor(name: String): ArtistDetailViewModel {
        val id = repository.observeArtistSummaries().first().first { it.artist.name == name }.artist.id
        return ArtistDetailViewModel(SavedStateHandle(mapOf("artistId" to id)), repository)
    }

    @Test
    fun `renaming an artist updates the name behind the screen`() = runTest(testDispatcher) {
        seed("DJ Seinfield")
        advanceUntilIdle()
        val viewModel = viewModelFor("DJ Seinfield")
        advanceUntilIdle()

        viewModel.onRenameRequested()
        viewModel.onRenameInputChange("DJ Seinfeld")
        viewModel.onRenameConfirmed()
        advanceUntilIdle()

        assertEquals("DJ Seinfeld", viewModel.uiState.value.artist?.name)
        assertNull("the dialog closes on confirm", viewModel.uiState.value.renameInput)
    }

    /** The whole point of having rename here: folding a misspelling into the real artist. */
    @Test
    fun `renaming onto an existing artist merges their shows`() = runTest(testDispatcher) {
        seed("DJ Seinfeld", LocalDate.of(2024, 1, 1))
        seed("DJ Seinfield", LocalDate.of(2025, 3, 1))
        advanceUntilIdle()
        val viewModel = viewModelFor("DJ Seinfield")
        advanceUntilIdle()

        viewModel.onRenameRequested()
        viewModel.onRenameInputChange("DJ Seinfeld")
        viewModel.onRenameConfirmed()
        advanceUntilIdle()

        val remaining = repository.observeArtistSummaries().first()
        assertEquals(listOf("DJ Seinfeld"), remaining.map { it.artist.name })
        assertEquals(2, remaining.single().timesSeen)
    }

    @Test
    fun `an unchanged or blank name cannot be confirmed`() = runTest(testDispatcher) {
        seed("Bedouin")
        advanceUntilIdle()
        val viewModel = viewModelFor("Bedouin")
        advanceUntilIdle()

        viewModel.onRenameRequested()
        assertFalse("the prefilled name is not a rename", viewModel.uiState.value.canConfirmRename)

        viewModel.onRenameInputChange("   ")
        assertFalse(viewModel.uiState.value.canConfirmRename)

        viewModel.onRenameInputChange("Bedouin ")
        assertFalse("trailing space is not a different name", viewModel.uiState.value.canConfirmRename)

        viewModel.onRenameInputChange("Bedouin Soundclash")
        assertTrue(viewModel.uiState.value.canConfirmRename)
    }

    /**
     * The bug this guards: clearing the artwork without clearing genres left the row out of the
     * backfill query, so a renamed artist showed initials for good.
     */
    @Test
    fun `renaming puts the artist back in the artwork queue`() = runTest(testDispatcher) {
        seed("Nghtmare")
        advanceUntilIdle()
        database.artistDao().setProfile(
            artistId = repository.observeArtistSummaries().first().single().artist.id,
            imageUrl = "https://img/wrong.jpg",
            genres = "dubstep",
            spotifyUrl = "https://open.spotify.com/wrong",
        )
        advanceUntilIdle()
        val viewModel = viewModelFor("Nghtmare")
        advanceUntilIdle()

        viewModel.onRenameRequested()
        viewModel.onRenameInputChange("NGHTMRE")
        viewModel.onRenameConfirmed()
        advanceUntilIdle()

        val queued = database.artistDao().observeArtistsWithoutProfile().first()
        assertEquals(listOf("NGHTMRE"), queued.map { it.name })
        assertNull("the old artist's portrait must not survive the rename", queued.single().imageUrl)
        assertNull("nor their profile link", queued.single().spotifyUrl)
    }

    // ---- Delete ------------------------------------------------------------------------

    /** The dialog has to name the damage before it happens, not after. */
    @Test
    fun `asking to delete counts the shows at stake first`() = runTest(testDispatcher) {
        seed("Bedouin")
        advanceUntilIdle()
        val viewModel = viewModelFor("Bedouin")
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()

        val impact = viewModel.uiState.value.deletionImpact!!
        assertTrue(viewModel.uiState.value.deleteRequested)
        assertEquals(1, impact.showsAffected)
        assertEquals("the show has nobody else on the bill", 1, impact.showsDeleted)
    }

    @Test
    fun `confirming deletes the artist and closes the screen`() = runTest(testDispatcher) {
        seed("Bedouin")
        advanceUntilIdle()
        val viewModel = viewModelFor("Bedouin")
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()
        viewModel.onDeleteConfirmed()
        advanceUntilIdle()

        assertTrue(repository.observeArtistSummaries().first().isEmpty())
        assertEquals(ArtistsEffect.Deleted, viewModel.effects.first())
    }

    @Test
    fun `dismissing the delete dialog leaves the artist alone`() = runTest(testDispatcher) {
        seed("Bedouin")
        advanceUntilIdle()
        val viewModel = viewModelFor("Bedouin")
        advanceUntilIdle()

        viewModel.onDeleteRequested()
        advanceUntilIdle()
        viewModel.onDeleteDismissed()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.deleteRequested)
        assertNull(viewModel.uiState.value.deletionImpact)
        assertEquals(1, repository.observeArtistSummaries().first().size)
    }

    @Test
    fun `dismissing leaves the name alone`() = runTest(testDispatcher) {
        seed("Bedouin")
        advanceUntilIdle()
        val viewModel = viewModelFor("Bedouin")
        advanceUntilIdle()

        viewModel.onRenameRequested()
        viewModel.onRenameInputChange("Something Else")
        viewModel.onRenameDismissed()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.renameInput)
        assertEquals("Bedouin", viewModel.uiState.value.artist?.name)
    }
}
