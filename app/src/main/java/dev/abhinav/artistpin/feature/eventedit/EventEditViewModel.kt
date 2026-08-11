package dev.abhinav.artistpin.feature.eventedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dev.abhinav.artistpin.core.designsystem.toUserMessage
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenueSuggestion
import dev.abhinav.artistpin.data.ArtistSuggestion
import dev.abhinav.artistpin.data.ConcertRepository
import dev.abhinav.artistpin.data.EventLinkImporter
import dev.abhinav.artistpin.data.ImportedEvent
import dev.abhinav.artistpin.data.EventDraft
import dev.abhinav.artistpin.data.RemoteArtistSearch
import dev.abhinav.artistpin.data.VenueSearchService
import dev.abhinav.artistpin.navigation.EventEditRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class ArtistRole { HEADLINER, SUPPORT }

/** One name on the bill. Role is a property of the entry, not a separate list it lives in. */
data class ArtistEntry(
    val name: String,
    val imageUrl: String? = null,
    val role: ArtistRole = ArtistRole.SUPPORT,
)

/** A venue that has been picked, with everything the picking filled in. */
data class SelectedVenue(
    val name: String,
    val city: String,
    val region: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val existingVenueId: String? = null,
    val existingCityId: String? = null,
) {
    val where: String get() = listOf(city, region, country).filter { it.isNotBlank() }.joinToString(" · ")
}

/** Which of the three date chips is on. The first two resolve against today at save time. */
enum class WhenChoice { TONIGHT, LAST_NIGHT, CUSTOM }

data class EventEditUiState(
    // Editable input
    val artists: List<ArtistEntry> = emptyList(),
    val artistQuery: String = "",
    val venue: SelectedVenue? = null,
    val venueQuery: String = "",
    val whenChoice: WhenChoice = WhenChoice.TONIGHT,
    /**
     * Resolved the moment a chip is tapped rather than at save time. "Tonight" means the night
     * you filled the form in — a form left open past midnight must not quietly re-date the show,
     * and an edit opened on a show dated today must not move it.
     */
    val date: LocalDate = LocalDate.now(),
    val title: String = "",

    // Derived, async
    val artistResults: List<ArtistSuggestion> = emptyList(),
    val isSearchingArtists: Boolean = false,
    val venueResults: List<VenueSuggestion> = emptyList(),
    val isSearchingVenues: Boolean = false,

    // Persisted snapshot — what already exists to reuse
    val knownVenues: List<Pair<Venue, City>> = emptyList(),
    val library: List<ArtistSummary> = emptyList(),
    val editingEventId: String? = null,

    // Transient UI-only
    val isSaving: Boolean = false,
    val showDatePicker: Boolean = false,
    val showLocationPicker: Boolean = false,
    val addressExpanded: Boolean = false,
    val extrasExpanded: Boolean = false,
    val venueSearchEnabled: Boolean = false,
    val isDirty: Boolean = false,
    val showDiscardPrompt: Boolean = false,
    val linkQuery: String = "",
    val isImportingLink: Boolean = false,
) {
    val isEditing: Boolean get() = editingEventId != null

    val headliners: List<String>
        get() = artists.filter { it.role == ArtistRole.HEADLINER }.map { it.name }

    val support: List<String>
        get() = artists.filter { it.role == ArtistRole.SUPPORT }.map { it.name }

    val isValid: Boolean get() = artists.isNotEmpty() && venue != null

    /**
     * Anything worth losing. A half-typed query counts: the user filled a box, and being dropped
     * out of the form without a word would feel the same as losing a finished one.
     */
    val hasUnsavedInput: Boolean
        get() = isDirty || artistQuery.isNotBlank() || venueQuery.isNotBlank()

    /**
     * What the one button at the bottom says. A disabled button that doesn't name what's missing
     * leaves the user hunting, so the label carries the reason.
     */
    val actionLabel: String
        get() = when {
            artists.isEmpty() -> "Add an artist to continue"
            venue == null -> "Pick a venue to continue"
            isEditing -> "Save changes"
            else -> "Add this show"
        }

    /** Local matches first — an artist you've seen is far likelier than a stranger with the same name. */
    val artistSuggestions: List<ArtistSuggestion>
        get() {
            val query = artistQuery.trim()
            if (query.isBlank()) return emptyList()
            val added = artists.map { it.name.lowercase() }.toSet()
            val local = library
                .filter { it.artist.name.contains(query, ignoreCase = true) }
                .sortedByDescending { it.timesSeen }
                .map {
                    ArtistSuggestion(
                        name = it.artist.name,
                        imageUrl = it.artist.imageUrl,
                        genres = it.artist.genres,
                        timesSeen = it.timesSeen,
                    )
                }
            val remote = artistResults.filter { result ->
                local.none { it.name.equals(result.name, ignoreCase = true) }
            }
            return (local + remote)
                .filterNot { it.name.lowercase() in added }
                .distinctBy { it.name.lowercase() }
                .take(MAX_SUGGESTIONS)
        }

    /**
     * True when the typed name matches nothing on offer, so the user can still add an act no
     * provider has heard of — the whole library started that way.
     */
    val canAddTypedArtist: Boolean
        get() {
            val query = artistQuery.trim()
            return query.isNotEmpty() &&
                artistSuggestions.none { it.name.equals(query, ignoreCase = true) } &&
                artists.none { it.name.equals(query, ignoreCase = true) }
        }

    /** Venues from your own history outrank anything a search can offer. */
    val knownVenueSuggestions: List<Pair<Venue, City>>
        get() = if (venueQuery.isBlank()) {
            emptyList()
        } else {
            knownVenues
                .filter { (venue, _) -> venue.name.contains(venueQuery, ignoreCase = true) }
                .take(3)
        }
}

sealed interface EventEditEffect {
    data class ShowMessage(val message: String) : EventEditEffect
    data class Saved(val eventId: String) : EventEditEffect
    data object NavigateBack : EventEditEffect
}

class EventEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConcertRepository,
    private val venueSearch: VenueSearchService,
    private val artistSearch: RemoteArtistSearch,
    private val linkImporter: EventLinkImporter,
) : ViewModel(), EventEditActions {

    private var venueSearchJob: Job? = null
    private var artistSearchJob: Job? = null

    private val editingEventId = savedStateHandle.toRoute<EventEditRoute>().eventId

    private val _uiState = MutableStateFlow(EventEditUiState(editingEventId = editingEventId))
    val uiState: StateFlow<EventEditUiState> = _uiState.asStateFlow()

    private val _effects = Channel<EventEditEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.observeKnownVenues().collect { venues ->
                _uiState.update { it.copy(knownVenues = venues) }
            }
        }
        viewModelScope.launch {
            repository.observeArtistSummaries().collect { summaries ->
                _uiState.update { it.copy(library = summaries) }
            }
        }
        _uiState.update { it.copy(venueSearchEnabled = venueSearch.isAvailable) }
        if (editingEventId != null) {
            viewModelScope.launch { loadExisting(editingEventId) }
        }
    }

    private suspend fun loadExisting(eventId: String) {
        val event = repository.observeEvent(eventId).first() ?: return
        val headliners = event.headliners.map { it.id }.toSet()
        _uiState.update { state ->
            state.copy(
                title = event.title.orEmpty(),
                extrasExpanded = !event.title.isNullOrBlank(),
                artists = event.artists.map { billing ->
                    ArtistEntry(
                        name = billing.artist.name,
                        imageUrl = billing.artist.imageUrl,
                        role = if (billing.artist.id in headliners) {
                            ArtistRole.HEADLINER
                        } else {
                            ArtistRole.SUPPORT
                        },
                    )
                },
                venue = SelectedVenue(
                    name = event.venue.name,
                    city = event.city.name,
                    region = event.city.region.orEmpty(),
                    country = event.city.country,
                    latitude = event.venue.latitude,
                    longitude = event.venue.longitude,
                    address = event.venue.address,
                    existingVenueId = event.venue.id,
                    existingCityId = event.city.id,
                ),
                whenChoice = when (event.date) {
                    LocalDate.now() -> WhenChoice.TONIGHT
                    LocalDate.now().minusDays(1) -> WhenChoice.LAST_NIGHT
                    else -> WhenChoice.CUSTOM
                },
                date = event.date,
            )
        }
    }

    // ---- Who did you see? ---------------------------------------------------------------

    // ---- Import from a ticket link -------------------------------------------------------

    override fun onLinkQueryChange(value: String) = _uiState.update { it.copy(linkQuery = value) }

    /**
     * Fills the form from a ticket page. Everything lands as ordinary editable state — the link
     * is a shortcut past the typing, not a source of truth that outranks the user.
     */
    override fun onImportLink() {
        val link = _uiState.value.linkQuery.trim()
        if (link.isEmpty() || _uiState.value.isImportingLink) return
        viewModelScope.launch {
            _uiState.update { it.copy(isImportingLink = true) }
            val result = linkImporter.import(link)
            _uiState.update { it.copy(isImportingLink = false) }
            when (result) {
                is DataResult.Failure ->
                    _effects.trySend(EventEditEffect.ShowMessage(result.error.toUserMessage()))
                is DataResult.Success -> {
                    applyImport(result.data)
                    _effects.trySend(
                        EventEditEffect.ShowMessage(
                            "Filled in from ${result.data.source} — check it before saving",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun applyImport(event: ImportedEvent) {
        _uiState.update { state ->
            state.copy(
                linkQuery = "",
                isDirty = true,
                title = event.title.ifBlank { state.title },
                extrasExpanded = state.extrasExpanded || event.title.isNotBlank(),
                // The bill arrives in running order, so the first name headlines exactly as it
                // does when you add artists by hand.
                artists = event.artists
                    .distinctBy { it.name.lowercase() }
                    .mapIndexed { index, artist ->
                        ArtistEntry(
                            name = artist.name,
                            imageUrl = artist.imageUrl,
                            role = if (index == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT,
                        )
                    }
                    .ifEmpty { state.artists },
                date = event.date ?: state.date,
                whenChoice = if (event.date != null) WhenChoice.CUSTOM else state.whenChoice,
            )
        }
        resolveImportedVenue(event)
    }

    /**
     * Takes only the venue's *name* from the ticket page and looks the place up properly.
     *
     * A ticket seller's idea of the city is its own: DICE files Knockdown Center under "New York",
     * while Places — and therefore every show already on the map — calls it Queens. Two names for
     * one place would split the city on the map, so the ticket page gets no say in it.
     */
    private suspend fun resolveImportedVenue(event: ImportedEvent) {
        if (event.venueName.isBlank()) return
        // The seller's city is still worth something as a search hint, just not as an answer.
        val query = listOf(event.venueName, event.city)
            .filter { it.isNotBlank() }
            .joinToString(", ")

        _uiState.update { it.copy(isSearchingVenues = true) }
        val match = (venueSearch.search(query) as? DataResult.Success)?.data?.firstOrNull()
        val resolved = match?.let { (venueSearch.resolve(it.placeId) as? DataResult.Success)?.data }
        _uiState.update { state ->
            if (resolved == null) {
                // Nothing to pin means nothing to save, so the name goes into the search field
                // rather than into a venue the user never confirmed.
                state.copy(isSearchingVenues = false, venue = null, venueQuery = event.venueName)
            } else {
                state.copy(
                    isSearchingVenues = false,
                    venueQuery = "",
                    venue = SelectedVenue(
                        name = resolved.name,
                        city = resolved.city,
                        region = resolved.region.orEmpty(),
                        country = resolved.country,
                        latitude = resolved.latitude,
                        longitude = resolved.longitude,
                        address = resolved.address,
                    ),
                )
            }
        }
    }

    override fun onArtistQueryChange(value: String) {
        _uiState.update { it.copy(artistQuery = value) }
        searchArtists(value)
    }

    /** Debounced so a burst of keystrokes costs one lookup. */
    private fun searchArtists(query: String) {
        artistSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < MIN_ARTIST_QUERY) {
            _uiState.update { it.copy(artistResults = emptyList(), isSearchingArtists = false) }
            return
        }
        artistSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update { it.copy(isSearchingArtists = true) }
            val results = artistSearch.search(trimmed)
            _uiState.update { it.copy(artistResults = results, isSearchingArtists = false) }
        }
    }

    override fun onArtistPicked(suggestion: ArtistSuggestion) =
        addArtist(suggestion.name, suggestion.imageUrl)

    /** The escape hatch for an act no provider knows: whatever was typed becomes the name. */
    override fun onAddTypedArtist() = addArtist(_uiState.value.artistQuery, imageUrl = null)

    private fun addArtist(name: String, imageUrl: String?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        artistSearchJob?.cancel()
        _uiState.update { state ->
            if (state.artists.any { it.name.equals(trimmed, ignoreCase = true) }) {
                return@update state.copy(artistQuery = "", artistResults = emptyList())
            }
            state.copy(
                // The first name on an empty bill is the one you went to see; the rest support it
                // until told otherwise.
                artists = state.artists + ArtistEntry(
                    name = trimmed,
                    imageUrl = imageUrl,
                    role = if (state.artists.isEmpty()) ArtistRole.HEADLINER else ArtistRole.SUPPORT,
                ),
                artistQuery = "",
                artistResults = emptyList(),
                isSearchingArtists = false,
                isDirty = true,
            )
        }
    }

    override fun onToggleArtistRole(name: String) {
        _uiState.update { state ->
            state.copy(
                artists = state.artists.map { entry ->
                    if (!entry.name.equals(name, ignoreCase = true)) {
                        entry
                    } else {
                        entry.copy(
                            role = if (entry.role == ArtistRole.HEADLINER) {
                                ArtistRole.SUPPORT
                            } else {
                                ArtistRole.HEADLINER
                            },
                        )
                    }
                },
                isDirty = true,
            )
        }
    }

    /**
     * Removing the last headliner promotes whoever is left, so a bill never ends up as support
     * acts with nobody to support — the show would have no name.
     */
    override fun onRemoveArtist(name: String) {
        _uiState.update { state ->
            val remaining = state.artists.filterNot { it.name.equals(name, ignoreCase = true) }
            val promoted = if (remaining.none { it.role == ArtistRole.HEADLINER }) {
                remaining.mapIndexed { index, entry ->
                    if (index == 0) entry.copy(role = ArtistRole.HEADLINER) else entry
                }
            } else {
                remaining
            }
            state.copy(artists = promoted, isDirty = true)
        }
    }

    // ---- Where? -------------------------------------------------------------------------

    override fun onVenueQueryChange(value: String) {
        _uiState.update { it.copy(venueQuery = value) }
        searchVenues(value)
    }

    private fun searchVenues(query: String) {
        venueSearchJob?.cancel()
        if (!venueSearch.isAvailable || query.length < MIN_VENUE_QUERY) {
            _uiState.update { it.copy(venueResults = emptyList(), isSearchingVenues = false) }
            return
        }
        venueSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update { it.copy(isSearchingVenues = true) }
            when (val result = venueSearch.search(query)) {
                is DataResult.Success -> _uiState.update {
                    it.copy(venueResults = result.data, isSearchingVenues = false)
                }
                is DataResult.Failure -> {
                    _uiState.update { it.copy(venueResults = emptyList(), isSearchingVenues = false) }
                    _effects.trySend(EventEditEffect.ShowMessage(result.error.toUserMessage()))
                }
            }
        }
    }

    /** One tap fills the venue, its city, region, country and coordinates together. */
    override fun onPlaceSuggestionPicked(suggestion: VenueSuggestion) {
        venueSearchJob?.cancel()
        _uiState.update { it.copy(venueResults = emptyList(), isSearchingVenues = true) }
        viewModelScope.launch {
            when (val result = venueSearch.resolve(suggestion.placeId)) {
                is DataResult.Success -> {
                    val place = result.data
                    _uiState.update {
                        it.copy(
                            isSearchingVenues = false,
                            venueQuery = "",
                            isDirty = true,
                            venue = SelectedVenue(
                                name = place.name,
                                city = place.city,
                                region = place.region.orEmpty(),
                                country = place.country,
                                latitude = place.latitude,
                                longitude = place.longitude,
                                address = place.address,
                                // A looked-up venue is a new row unless its name and city match
                                // one already visited; the repository dedupes on save.
                                existingVenueId = null,
                                existingCityId = null,
                            ),
                        )
                    }
                }
                is DataResult.Failure -> {
                    _uiState.update { it.copy(isSearchingVenues = false) }
                    _effects.trySend(EventEditEffect.ShowMessage(result.error.toUserMessage()))
                }
            }
        }
    }

    override fun onKnownVenuePicked(venue: Venue, city: City) {
        venueSearchJob?.cancel()
        _uiState.update {
            it.copy(
                venueResults = emptyList(),
                isSearchingVenues = false,
                venueQuery = "",
                isDirty = true,
                venue = SelectedVenue(
                    name = venue.name,
                    city = city.name,
                    region = city.region.orEmpty(),
                    country = city.country,
                    latitude = venue.latitude,
                    longitude = venue.longitude,
                    address = venue.address,
                    existingVenueId = venue.id,
                    existingCityId = city.id,
                ),
            )
        }
    }

    /** Dirty like any other edit: dropping the venue is exactly the change back must ask about. */
    override fun onClearVenue() = _uiState.update {
        it.copy(
            venue = null,
            venueQuery = "",
            venueResults = emptyList(),
            addressExpanded = false,
            isDirty = true,
        )
    }

    override fun onToggleAddress() = _uiState.update { it.copy(addressExpanded = !it.addressExpanded) }

    /** A manual edit overrides the geocode and survives the save. */
    override fun onCityChange(value: String) = updateVenue { it.copy(city = value, existingCityId = null) }
    override fun onRegionChange(value: String) = updateVenue { it.copy(region = value) }
    override fun onCountryChange(value: String) = updateVenue { it.copy(country = value) }

    private fun updateVenue(transform: (SelectedVenue) -> SelectedVenue) {
        _uiState.update { state ->
            state.copy(venue = state.venue?.let(transform), isDirty = true)
        }
    }

    override fun onShowLocationPicker() = _uiState.update { it.copy(showLocationPicker = true) }
    override fun onDismissLocationPicker() = _uiState.update { it.copy(showLocationPicker = false) }
    override fun onLocationPicked(latitude: Double, longitude: Double) {
        _uiState.update { state ->
            state.copy(
                showLocationPicker = false,
                isDirty = true,
                venue = state.venue?.copy(latitude = latitude, longitude = longitude),
            )
        }
    }

    // ---- When? --------------------------------------------------------------------------

    override fun onWhenChosen(choice: WhenChoice) {
        if (choice == WhenChoice.CUSTOM) {
            _uiState.update { it.copy(showDatePicker = true) }
            return
        }
        val resolved = when (choice) {
            WhenChoice.TONIGHT -> LocalDate.now()
            WhenChoice.LAST_NIGHT -> LocalDate.now().minusDays(1)
            WhenChoice.CUSTOM -> return
        }
        _uiState.update { it.copy(whenChoice = choice, date = resolved, isDirty = true) }
    }

    override fun onDismissDatePicker() = _uiState.update { it.copy(showDatePicker = false) }

    override fun onDateSelected(date: LocalDate) = _uiState.update {
        it.copy(
            date = date,
            whenChoice = WhenChoice.CUSTOM,
            showDatePicker = false,
            isDirty = true,
        )
    }

    // ---- Festival name ------------------------------------------------------------------

    override fun onToggleExtras() = _uiState.update { it.copy(extrasExpanded = !it.extrasExpanded) }
    override fun onTitleChange(value: String) = _uiState.update { it.copy(title = value, isDirty = true) }

    // ---- Leaving and saving --------------------------------------------------------------

    override fun onBackRequested() {
        if (_uiState.value.hasUnsavedInput) {
            _uiState.update { it.copy(showDiscardPrompt = true) }
        } else {
            _effects.trySend(EventEditEffect.NavigateBack)
        }
    }

    override fun onDiscardDismissed() = _uiState.update { it.copy(showDiscardPrompt = false) }

    override fun onDiscardConfirmed() {
        _uiState.update { it.copy(showDiscardPrompt = false) }
        _effects.trySend(EventEditEffect.NavigateBack)
    }

    override fun onSave() {
        val state = _uiState.value
        if (state.isSaving || !state.isValid) return
        val venue = state.venue ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = repository.saveEvent(
                EventDraft(
                    eventId = state.editingEventId,
                    date = state.date,
                    cityName = venue.city,
                    country = venue.country,
                    region = venue.region.takeIf { it.isNotBlank() },
                    existingCityId = venue.existingCityId,
                    venueName = venue.name,
                    latitude = venue.latitude,
                    longitude = venue.longitude,
                    address = venue.address?.takeIf { it.isNotBlank() },
                    existingVenueId = venue.existingVenueId,
                    artistNames = state.headliners,
                    supportArtistNames = state.support,
                    title = state.title.takeIf { it.isNotBlank() },
                ),
            )
            _uiState.update { it.copy(isSaving = false) }
            when (result) {
                is DataResult.Success -> _effects.trySend(EventEditEffect.Saved(result.data))
                is DataResult.Failure -> _effects.trySend(
                    EventEditEffect.ShowMessage(result.error.toUserMessage()),
                )
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
        const val MIN_ARTIST_QUERY = 2
        const val MIN_VENUE_QUERY = 3
    }
}

private const val MAX_SUGGESTIONS = 6
