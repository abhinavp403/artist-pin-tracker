package dev.abhinav.artistpin.data

import com.google.android.libraries.places.api.model.AddressComponent
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.ResolvedVenue
import dev.abhinav.artistpin.core.model.VenueSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PlacesVenueSearchService(
    private val clientProvider: () -> PlacesClient?,
    private val ioDispatcher: CoroutineDispatcher,
) : VenueSearchService {

    /**
     * One token spans every keystroke of a search plus the resolve that ends it, so Google bills
     * the whole interaction once instead of per request. It must be discarded after each resolve.
     */
    private var sessionToken: AutocompleteSessionToken? = null

    override val isAvailable: Boolean get() = clientProvider() != null

    override suspend fun search(query: String): DataResult<List<VenueSuggestion>> =
        withContext(ioDispatcher) {
            val client = clientProvider() ?: return@withContext DataResult.Failure(DataError.SearchUnavailable)
            if (query.isBlank()) return@withContext DataResult.Success(emptyList())

            val token = sessionToken ?: AutocompleteSessionToken.newInstance().also { sessionToken = it }
            try {
                val response = client.findAutocompletePredictions(
                    FindAutocompletePredictionsRequest.builder()
                        .setQuery(query)
                        .setSessionToken(token)
                        // Venues are businesses, not addresses — this keeps street results out.
                        .setTypesFilter(listOf(ESTABLISHMENT))
                        .build(),
                ).await()

                DataResult.Success(
                    response.autocompletePredictions.map { prediction ->
                        VenueSuggestion(
                            placeId = prediction.placeId,
                            name = prediction.getPrimaryText(null).toString(),
                            address = prediction.getSecondaryText(null).toString(),
                        )
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataResult.Failure(DataError.Network(e.message))
            }
        }

    override suspend fun resolve(placeId: String): DataResult<ResolvedVenue> =
        withContext(ioDispatcher) {
            val client = clientProvider() ?: return@withContext DataResult.Failure(DataError.SearchUnavailable)
            try {
                val request = FetchPlaceRequest.builder(placeId, PLACE_FIELDS)
                    .apply { sessionToken?.let { setSessionToken(it) } }
                    .build()
                val place = client.fetchPlace(request).await().place
                sessionToken = null

                val location = place.location
                    ?: return@withContext DataResult.Failure(
                        DataError.Validation("That place has no location on file"),
                    )

                val components = place.addressComponents?.asList().orEmpty()
                DataResult.Success(
                    ResolvedVenue(
                        name = place.displayName ?: place.formattedAddress.orEmpty(),
                        address = place.formattedAddress,
                        city = normalizeCity(components.firstMatching(CITY_TYPES)?.name.orEmpty()),
                        region = components.firstMatching(REGION_TYPES)?.shortName,
                        country = components.firstMatching(COUNTRY_TYPES)?.name.orEmpty(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionToken = null
                DataResult.Failure(DataError.Network(e.message))
            }
        }

    /**
     * Folds municipalities that are their own city on paper but one place in practice, so they
     * share a single map pin instead of splitting into two that sit on top of each other.
     */
    private fun normalizeCity(city: String): String =
        CITY_ALIASES[city.lowercase()] ?: city

    /** Address component types vary by country, so each field tries a prioritized list. */
    private fun List<AddressComponent>.firstMatching(types: List<String>): AddressComponent? =
        types.firstNotNullOfOrNull { type -> firstOrNull { type in it.types } }

    private companion object {
        const val ESTABLISHMENT = "establishment"

        val PLACE_FIELDS = listOf(
            Place.Field.DISPLAY_NAME,
            Place.Field.FORMATTED_ADDRESS,
            Place.Field.LOCATION,
            Place.Field.ADDRESS_COMPONENTS,
        )

        val CITY_ALIASES = mapOf("miami beach" to "Miami")

        // "locality" covers most of the world; "postal_town" is how the UK names its towns.
        val CITY_TYPES = listOf("locality", "postal_town", "sublocality", "administrative_area_level_2")
        val REGION_TYPES = listOf("administrative_area_level_1")
        val COUNTRY_TYPES = listOf("country")
    }
}
