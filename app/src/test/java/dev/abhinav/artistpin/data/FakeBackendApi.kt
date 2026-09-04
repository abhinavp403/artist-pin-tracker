package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.data.backend.ArtistDeletionImpactDto
import dev.abhinav.artistpin.data.backend.ArtistDto
import dev.abhinav.artistpin.data.backend.ArtistSummaryDto
import dev.abhinav.artistpin.data.backend.BackendApi
import dev.abhinav.artistpin.data.backend.CityPinDto
import dev.abhinav.artistpin.data.backend.EventDetailDto
import dev.abhinav.artistpin.data.backend.EventMediaDto
import dev.abhinav.artistpin.data.backend.EventSummaryDto
import dev.abhinav.artistpin.data.backend.ImportResultDto
import kotlinx.serialization.json.JsonObject
import dev.abhinav.artistpin.data.backend.SaveEventParams
import dev.abhinav.artistpin.data.backend.VenueDto
import dev.abhinav.artistpin.data.backend.VenuePinDto
import java.io.IOException

/**
 * An in-memory [BackendApi]. The reason `BackendApi` is an interface at all — none of the
 * repository's behaviour needs a network or a project to test.
 */
class FakeBackendApi : BackendApi {

    var cityPins: List<CityPinDto> = emptyList()
    var venuePins: List<VenuePinDto> = emptyList()
    var venues: List<VenueDto> = emptyList()
    var artistsWithoutProfile: List<ArtistDto> = emptyList()

    /** When set, the next read throws it and then clears. Models a dropped connection. */
    var failNextRead: Exception? = null

    /** Events by id. `event(id)` returns null for anything absent, like a deleted row. */
    var events: MutableMap<String, EventDetailDto> = mutableMapOf()

    /** Ids whose `event()` lookup throws instead of answering — a failed check, not a deletion. */
    var failEventLookupFor: MutableSet<String> = mutableSetOf()

    var eventsForArtist: List<EventSummaryDto> = emptyList()
    var failEventsForArtist: Exception? = null

    var savedParams: SaveEventParams? = null
    var saveCallCount = 0
    var renamedTo: Pair<String, String>? = null
    var deletedArtistId: String? = null
    var profileWrites = mutableListOf<String>()

    private fun <T> read(value: T): T {
        failNextRead?.let { failNextRead = null; throw it }
        return value
    }

    override suspend fun cityPins() = read(cityPins)

    override suspend fun venuePins(cityId: String?) = read(venuePins)

    override suspend fun events(cityId: String?, venueId: String?, artistId: String?):
        List<EventSummaryDto> {
        if (artistId != null) {
            failEventsForArtist?.let { failEventsForArtist = null; throw it }
            return eventsForArtist
        }
        return read(emptyList())
    }

    override suspend fun event(eventId: String): EventDetailDto? {
        if (eventId in failEventLookupFor) throw IOException("lookup failed")
        return events[eventId]
    }

    override suspend fun artistSummaries(): List<ArtistSummaryDto> = read(emptyList())

    override suspend fun artist(artistId: String): ArtistDto? = read(null)

    override suspend fun artists(): List<ArtistDto> = read(emptyList())

    override suspend fun artistsWithoutProfile(): List<ArtistDto> = read(artistsWithoutProfile)

    override suspend fun venues(): List<VenueDto> = read(venues)

    override suspend fun mediaFor(eventId: String): List<EventMediaDto> = read(emptyList())

    /** Fails the next saveEvent and then clears — models a drop mid-drain. */
    var failNextSave: Exception? = null

    override suspend fun saveEvent(params: SaveEventParams): String {
        failNextSave?.let { failNextSave = null; throw it }
        savedParams = params
        saveCallCount++
        return params.eventId ?: "generated-event-id"
    }

    /** Event ids whose delete is refused, standing in for the server rejecting an unknown event. */
    var failDeleteEventFor: MutableSet<String> = mutableSetOf()
    val deletedEventIds = mutableListOf<String>()

    override suspend fun deleteEvent(eventId: String) {
        if (eventId in failDeleteEventFor) throw IllegalStateException("no such event")
        deletedEventIds += eventId
    }

    /** Names the sync asked the catalog to resolve, in order. */
    val findOrCreateCalls = mutableListOf<String>()

    override suspend fun findOrCreateArtist(name: String): String {
        findOrCreateCalls += name
        failNextRead?.let { failNextRead = null; throw it }
        return "catalog-id-for-${name.lowercase()}"
    }

    override suspend fun renameArtist(artistId: String, newName: String): String {
        renamedTo = artistId to newName
        return "target-artist-id"
    }

    override suspend fun previewArtistDeletion(artistId: String) =
        read(ArtistDeletionImpactDto(showsAffected = 2, showsDeleted = 1))

    override suspend fun deleteArtist(artistId: String) {
        deletedArtistId = artistId
    }

    var importedPayload: JsonObject? = null
    var importResult = ImportResultDto()

    override suspend fun importBackup(payload: JsonObject): ImportResultDto {
        importedPayload = payload
        failNextRead?.let { failNextRead = null; throw it }
        return importResult
    }

    /** What `export_backup()` would hand back. Set per test. */
    var exportPayload: JsonObject = JsonObject(emptyMap())

    override suspend fun exportBackup(): JsonObject {
        failNextRead?.let { failNextRead = null; throw it }
        return exportPayload
    }

    override suspend fun addMedia(rows: List<EventMediaDto>) = Unit

    override suspend fun deleteMedia(mediaId: String) = Unit

    /** Uploaded object paths, in order, so a test can assert where bytes were put. */
    val uploads = mutableListOf<String>()
    var failUpload: Exception? = null
    val storagePathWrites = mutableListOf<Pair<String, String>>()

    override suspend fun uploadMedia(path: String, bytes: ByteArray, mimeType: String) {
        failUpload?.let { throw it }
        uploads += path
    }

    override suspend fun setMediaStoragePath(mediaId: String, path: String) {
        storagePathWrites += mediaId to path
    }

    override suspend fun signedMediaUrl(path: String, expiresInSeconds: Long): String =
        "https://signed.example/$path?token=fake"

    override suspend fun deleteStoredMedia(paths: List<String>) = Unit

    override suspend fun setArtistProfile(
        artistId: String,
        imageUrl: String?,
        genres: String,
        spotifyUrl: String?,
    ) {
        profileWrites += artistId
    }

    companion object {
        fun offline() = IOException("no route to host")
    }
}
