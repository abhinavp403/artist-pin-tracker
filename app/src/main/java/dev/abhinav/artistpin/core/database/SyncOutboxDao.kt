package dev.abhinav.artistpin.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOutboxDao {

    @Insert
    suspend fun enqueue(entry: SyncOutboxEntity): Long

    /**
     * Oldest first. Order is load-bearing: a show has to be created before a later edit to it can
     * be applied, and deleting an event before its own creation has been sent would fail.
     */
    @Query("SELECT * FROM sync_outbox ORDER BY id ASC")
    suspend fun pending(): List<SyncOutboxEntity>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun remove(id: Long)

    /**
     * Drops earlier queued entries for the same thing.
     *
     * Editing one show five times offline should send one save, not five: each payload is the whole
     * draft, so only the last one carries any information. Without this the queue grows with every
     * keystroke-level save and replays a history nobody needs.
     */
    @Query("DELETE FROM sync_outbox WHERE entityId = :entityId AND operation = :operation")
    suspend fun removeSuperseded(entityId: String, operation: String)

    /** Everything queued for a thing that has since been deleted — no point sending edits to it. */
    @Query("DELETE FROM sync_outbox WHERE entityId = :entityId")
    suspend fun removeAllFor(entityId: String)

    /** Whether something is already queued for this thing, so the backfill does not re-queue it. */
    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entityId = :entityId AND operation = :operation")
    suspend fun countFor(entityId: String, operation: String): Int

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String?)

    @Query("DELETE FROM sync_outbox")
    suspend fun clear()
}
