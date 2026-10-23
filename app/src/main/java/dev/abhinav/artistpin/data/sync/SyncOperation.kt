package dev.abhinav.artistpin.data.sync

import kotlinx.serialization.Serializable

/**
 * What a queued change is.
 *
 * Names rather than ids for anything touching the shared catalog — see the note on
 * `SyncOutboxEntity`. The local id of a venue created offline means nothing to a server that
 * deduplicates venues by name.
 */
enum class SyncOperation {
    SAVE_EVENT,
    DELETE_EVENT,
    RENAME_ARTIST,
    DELETE_ARTIST,
    ADD_MEDIA,
    REMOVE_MEDIA,

    /**
     * Send one photo's bytes to object storage.
     *
     * Separate from ADD_MEDIA, which only sends the row. The row is small and syncs in a moment;
     * the file can be several megabytes and may need many retries on a bad connection, and tying
     * the two together would mean a failed upload blocking a lineup edit behind it.
     */
    UPLOAD_MEDIA,

    /**
     * Remove uploaded bytes from the bucket.
     *
     * Queued rather than done inline so a delete survives being offline — otherwise deleting a
     * photo on the underground would leave its bytes in storage forever, and the user would be
     * told it was gone.
     */
    DELETE_STORED_MEDIA,

    /**
     * Send artwork and genres up to the shared catalog.
     *
     * Each device looks an artist up for itself the first time it meets one, and until this
     * existed that result stayed on the device: the catalog row kept a null image, and the next
     * full pull — which rebuilds the artist table from the server — put that null back over the
     * portrait that had just been fetched. The lookup would then not run again until the app was
     * restarted, so a newly added artist showed initials for the rest of the session.
     *
     * `catalog_set_artist_profile` only fills blanks, so this can never overwrite a better answer
     * somebody else's lookup already found.
     */
    SET_ARTIST_PROFILE,
}

/**
 * A show, in the shape `save_event` wants.
 *
 * Carries the resolved event id — Room generated it locally the moment the user pressed save, and
 * sending the same id lets the server insert it verbatim. That is what makes replay idempotent: a
 * queue entry sent twice is one show, not two.
 */
@Serializable
data class SaveEventPayload(
    val eventId: String,
    val eventDate: String,
    val cityName: String,
    val country: String,
    val region: String? = null,
    val venueName: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val artistNames: List<String>,
    val supportNames: List<String> = emptyList(),
    val title: String? = null,
)

@Serializable
data class DeleteEventPayload(val eventId: String)

/**
 * Rename carries both names, not the local artist id.
 *
 * The server resolves [previousName] through the catalog's find-or-create to get the id it knows,
 * then repoints the caller's rows. Sending a Room id would name a row the catalog has never heard
 * of.
 */
@Serializable
data class RenameArtistPayload(val previousName: String, val newName: String)

@Serializable
data class DeleteArtistPayload(val artistName: String)

@Serializable
data class MediaRowPayload(
    val id: String,
    val eventId: String,
    val localPath: String,
    val originalUri: String,
    val mimeType: String,
    val capturedAtEpochMillis: Long? = null,
    val sortIndex: Int = 0,
)

@Serializable
data class AddMediaPayload(val eventId: String, val rows: List<MediaRowPayload>)

@Serializable
data class RemoveMediaPayload(val mediaId: String)

/**
 * Carries the path on disk rather than the bytes: the queue lives in SQLite, and a few megabytes of
 * base64 per photo would bloat the database and be read back on every drain. The file is read at
 * send time, and a file that has since vanished simply drops the entry.
 */
@Serializable
data class DeleteStoredMediaPayload(val paths: List<String>)

@Serializable
data class UploadMediaPayload(
    val mediaId: String,
    val eventId: String,
    val localPath: String,
    val mimeType: String,
)

/**
 * One artist's resolved artwork, by name.
 *
 * By name rather than by id for the same reason [RenameArtistPayload] is: the id Room generated
 * when this artist was first typed in is meaningless to a catalog that deduplicates by name.
 */
@Serializable
data class SetArtistProfilePayload(
    val artistName: String,
    val imageUrl: String? = null,
    val genres: String = "",
    val spotifyUrl: String? = null,
)
