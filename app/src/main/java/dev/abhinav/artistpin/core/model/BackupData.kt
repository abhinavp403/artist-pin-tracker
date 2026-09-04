package dev.abhinav.artistpin.core.model

import kotlinx.serialization.Serializable

/**
 * The on-disk backup format. Rows are stored flat rather than nested so the file stays readable
 * and a future version can add a table without breaking older ones.
 */
@Serializable
data class BackupData(
    val version: Int = CURRENT_VERSION,
    val exportedAtEpochMillis: Long = 0,
    val cities: List<BackupCity> = emptyList(),
    val venues: List<BackupVenue> = emptyList(),
    val artists: List<BackupArtist> = emptyList(),
    val events: List<BackupEvent> = emptyList(),
    val eventArtists: List<BackupEventArtist> = emptyList(),
    /**
     * Photo files are deliberately not in the backup — only the rows that point at them. On
     * import, entries whose file is missing are dropped, so restoring onto a fresh install
     * silently discards them instead of leaving broken thumbnails.
     */
    val media: List<BackupMedia> = emptyList(),
) {
    val summary: BackupSummary
        get() = BackupSummary(
            shows = events.size,
            artists = artists.size,
            venues = venues.size,
            cities = cities.size,
        )

    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class BackupSummary(
    val shows: Int,
    val artists: Int,
    val venues: Int,
    val cities: Int,
)

@Serializable
data class BackupCity(
    val id: String,
    val name: String,
    val country: String,
    val region: String? = null,
)

@Serializable
data class BackupVenue(
    val id: String,
    val name: String,
    val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

@Serializable
data class BackupArtist(
    val id: String,
    val name: String,
    val imageUrl: String? = null,
    val genres: String? = null,
    val spotifyUrl: String? = null,
)

@Serializable
data class BackupEvent(
    val id: String,
    val venueId: String,
    val dateEpochDay: Long,
    val title: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
)

@Serializable
data class BackupEventArtist(
    val eventId: String,
    val artistId: String,
    val billing: String,
)

@Serializable
data class BackupMedia(
    val id: String,
    val eventId: String,
    val localPath: String,
    val originalUri: String,
    val mimeType: String,
    val capturedAt: Long? = null,
    val sortIndex: Int = 0,
    /**
     * Where the bytes live in object storage. Optional, so files written before Milestone D still
     * parse — and load-bearing for the sync's pull, which rebuilds Room from this shape: omit it and
     * every photo looks un-uploaded and gets queued again on every refresh.
     */
    val storagePath: String? = null,
)
