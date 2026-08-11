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
}
