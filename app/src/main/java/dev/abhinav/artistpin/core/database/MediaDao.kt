package dev.abhinav.artistpin.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("SELECT * FROM event_media WHERE eventId = :eventId ORDER BY sortIndex ASC")
    fun observeMediaForEvent(eventId: String): Flow<List<EventMediaEntity>>

    @Query("SELECT * FROM event_media WHERE eventId = :eventId ORDER BY sortIndex ASC")
    suspend fun getMediaForEvent(eventId: String): List<EventMediaEntity>

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM event_media WHERE eventId = :eventId")
    suspend fun maxSortIndex(eventId: String): Int

    @Query("SELECT originalUri FROM event_media WHERE eventId = :eventId")
    suspend fun originalUris(eventId: String): List<String>

    @Query("SELECT * FROM event_media")
    suspend fun allMedia(): List<EventMediaEntity>

    @Upsert
    suspend fun upsertMedia(media: List<EventMediaEntity>)

    @Query("DELETE FROM event_media WHERE id = :mediaId")
    suspend fun deleteMedia(mediaId: String)

    @Query("SELECT * FROM event_media WHERE id = :mediaId")
    suspend fun media(mediaId: String): EventMediaEntity?

    /**
     * Photos whose bytes are still only on this phone. Limited because the backfill can be
     * hundreds of items on a library this old, and queueing them all at once would make one sync
     * run responsible for the entire upload.
     */
    /**
     * Photos awaiting upload. Videos are excluded by the `mimeType` clause, not by an oversight:
     * they are routinely larger than the bucket's 50 MB object limit — which is also the free
     * plan's ceiling and so cannot be raised — and uploading the ones that happen to fit would
     * spend most of the storage quota on a handful of clips. They stay on the device.
     */
    @Query(
        """
        SELECT * FROM event_media
         WHERE remotePath IS NULL
           AND uploadAttempts < :maxAttempts
           AND mimeType NOT LIKE 'video/%'
         ORDER BY id LIMIT :limit
        """,
    )
    suspend fun awaitingUpload(limit: Int, maxAttempts: Int): List<EventMediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM event_media
         WHERE remotePath IS NULL
           AND uploadAttempts < :maxAttempts
           AND mimeType NOT LIKE 'video/%'
        """,
    )
    fun observeAwaitingUploadCount(maxAttempts: Int): Flow<Int>

    /** Marks one more failed attempt, so a doomed upload eventually stops being re-queued. */
    @Query("UPDATE event_media SET uploadAttempts = uploadAttempts + 1 WHERE id = :mediaId")
    suspend fun recordUploadFailure(mediaId: String)

    /** Retires a photo from the upload queue outright — used when its file no longer exists. */
    @Query("UPDATE event_media SET uploadAttempts = :maxAttempts WHERE id = :mediaId")
    suspend fun abandonUpload(mediaId: String, maxAttempts: Int)

    @Query("SELECT remotePath FROM event_media WHERE id = :mediaId")
    suspend fun remotePathFor(mediaId: String): String?

    @Query("SELECT remotePath FROM event_media WHERE eventId = :eventId AND remotePath IS NOT NULL")
    suspend fun remotePathsForEvent(eventId: String): List<String>

    @Query("UPDATE event_media SET remotePath = :path WHERE id = :mediaId")
    suspend fun setRemotePath(mediaId: String, path: String)
}
