package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.ResolvedVenue
import dev.abhinav.artistpin.core.model.VenueSuggestion

class FakeVenueSearchService(
    override var isAvailable: Boolean = true,
) : VenueSearchService {

    var searchCallCount = 0
        private set
    var lastQuery: String? = null
        private set

    var results: List<VenueSuggestion> = listOf(
        VenueSuggestion(
            placeId = "place-lff",
            name = "Lincoln Financial Field",
            address = "1 Lincoln Financial Field Way, Philadelphia, PA",
        ),
    )

    var resolved: ResolvedVenue = ResolvedVenue(
        name = "Lincoln Financial Field",
        address = "1 Lincoln Financial Field Way, Philadelphia, PA 19148",
        city = "Philadelphia",
        region = "PA",
        country = "United States",
        latitude = 39.9008,
        longitude = -75.1675,
    )

    var searchError: DataError? = null
    var resolveError: DataError? = null

    override suspend fun search(query: String): DataResult<List<VenueSuggestion>> {
        searchCallCount++
        lastQuery = query
        return searchError?.let { DataResult.Failure(it) } ?: DataResult.Success(results)
    }

    override suspend fun resolve(placeId: String): DataResult<ResolvedVenue> =
        resolveError?.let { DataResult.Failure(it) } ?: DataResult.Success(resolved)
}
