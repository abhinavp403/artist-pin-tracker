package dev.abhinav.artistpin.data

import android.net.Uri
import dev.abhinav.artistpin.core.model.Artist
import dev.abhinav.artistpin.core.model.ArtistDeletionImpact
import dev.abhinav.artistpin.core.model.ArtistSummary
import dev.abhinav.artistpin.core.model.City
import dev.abhinav.artistpin.core.model.CityPin
import dev.abhinav.artistpin.core.model.ConcertEvent
import dev.abhinav.artistpin.core.model.DataResult
import dev.abhinav.artistpin.core.model.EventSummary
import dev.abhinav.artistpin.core.model.Venue
import dev.abhinav.artistpin.core.model.VenuePin
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Everything the add/edit screen needs to describe one show. */
data class EventDraft(
    val eventId: String? = null,
    val date: LocalDate,
    val cityName: String,
    val country: String,
    val region: String? = null,
    val existingCityId: String? = null,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val existingVenueId: String? = null,
    val artistNames: List<String>,
    val supportArtistNames: List<String> = emptyList(),
    val title: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
)

/**
 * The boundary every screen goes through. No ViewModel knows whether the shows behind it live in
 * Room on this device or in Postgres behind an account.
 *
 * This was a concrete class until B6, and extracting the interface changed nothing above it — the
 * ViewModels already depended on exactly this surface, which is the whole reason the backend
 * migration is a swap rather than a rewrite.
 *
 * Two implementations: [RoomConcertRepository] (device-local, the original) and
 * [BackendConcertRepository] (Supabase). Which one is bound is a build-time choice; see
 * `dataModule` in `Modules.kt`.
 */
interface ConcertRepository {

    fun observeCityPins(): Flow<List<CityPin>>

    fun observeVenuePins(cityId: String): Flow<List<VenuePin>>

    fun observeAllVenuePins(): Flow<List<VenuePin>>

    /**
     * Long-running: watches for artists with no artwork and fills them in as they appear. Never
     * returns under normal operation.
     */
    suspend fun keepArtistArtworkFresh()

    fun observeEventsInCity(cityId: String): Flow<List<EventSummary>>

    fun observeEventsAtVenue(venueId: String): Flow<List<EventSummary>>

    fun observeAllEvents(): Flow<List<EventSummary>>

    fun observeEventsForArtist(artistId: String): Flow<List<EventSummary>>

    fun observeArtistSummaries(): Flow<List<ArtistSummary>>

    fun observeArtist(artistId: String): Flow<Artist?>

    fun observeKnownArtists(): Flow<List<Artist>>

    fun observeKnownVenues(): Flow<List<Pair<Venue, City>>>

    fun observeEvent(eventId: String): Flow<ConcertEvent?>

    suspend fun saveEvent(draft: EventDraft): DataResult<String>

    /** Counted before the confirmation dialog opens, so it can say what will be lost. */
    suspend fun previewArtistDeletion(artistId: String): DataResult<ArtistDeletionImpact>

    suspend fun deleteArtist(artistId: String): DataResult<Unit>

    suspend fun renameArtist(artistId: String, newName: String): DataResult<Unit>

    suspend fun deleteEvent(eventId: String): DataResult<Unit>

    /** Returns how many items were newly imported; already-imported URIs are skipped. */
    suspend fun addMedia(eventId: String, uris: List<Uri>): DataResult<Int>

    suspend fun removeMedia(mediaId: String, localPath: String): DataResult<Unit>
}
