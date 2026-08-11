package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.ResolvedVenue
import dev.abhinav.artistpin.core.model.VenueSuggestion

/**
 * Venue lookup, kept behind an interface so the Places SDK stays out of the ViewModel and the
 * search flow can be faked in tests.
 */
interface VenueSearchService {

    /** False when no Places key is configured — the UI then falls back to manual entry. */
    val isAvailable: Boolean

    suspend fun search(query: String): DataResult<List<VenueSuggestion>>

    /** Fetches coordinates and address parts, ending the billed autocomplete session. */
    suspend fun resolve(placeId: String): DataResult<ResolvedVenue>
}
