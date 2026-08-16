package dev.abhinav.artistpin.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.abhinav.artistpin.core.model.Billing

class Converters {
    @TypeConverter
    fun billingToString(billing: Billing): String = billing.name

    @TypeConverter
    fun stringToBilling(value: String): Billing =
        runCatching { Billing.valueOf(value) }.getOrDefault(Billing.HEADLINER)
}

@Database(
    entities = [
        CityEntity::class,
        VenueEntity::class,
        ArtistEntity::class,
        EventEntity::class,
        EventArtistCrossRef::class,
        EventMediaEntity::class,
        SyncOutboxEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ArtistPinDatabase : RoomDatabase() {
    abstract fun concertDao(): ConcertDao
    abstract fun artistDao(): ArtistDao
    abstract fun mediaDao(): MediaDao
    abstract fun syncOutboxDao(): SyncOutboxDao

    companion object {
        const val NAME = "artistpin.db"

        /**
         * Adds the sync queue. Purely additive — no existing table is touched, so an upgrade
         * carries every show across untouched and simply starts with nothing queued, which is the
         * correct state for a library that is already in step with the backend.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operation TEXT NOT NULL,
                        entityId TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        queuedAtEpochMillis INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_outbox_entityId ON sync_outbox (entityId)",
                )
            }
        }

        /**
         * Renaming used to clear the artwork without clearing genres, which is what re-queues an
         * artist for lookup — so every renamed artist was left showing initials permanently.
         * Anyone stranded in that state is put back in the queue here.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "UPDATE artists SET genres = NULL, spotifyUrl = NULL WHERE imageUrl IS NULL",
                )
            }
        }

        /**
         * Dixon and Bedouin had been resolved to the wrong artists entirely — a country singer
         * and a blues musician respectively — so their cached portrait, link and genres are all
         * wrong rather than merely stale. Clearing every profile column puts them back in the
         * backfill query, which now resolves them by pinned ID.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    UPDATE artists
                    SET genres = NULL, imageUrl = NULL, spotifyUrl = NULL
                    WHERE LOWER(name) IN ('dixon', 'bedouin')
                    """.trimIndent(),
                )
            }
        }

        /**
         * Spotify turned out to have removed genres from its API, so every artist was marked
         * "looked up, none found". Clearing the column re-arms the backfill now that MusicBrainz
         * supplies them. Artwork and Spotify links are untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE artists SET genres = NULL")
            }
        }

        /** Adds genres and a Spotify link. Nullable so existing artists just backfill on next launch. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE artists ADD COLUMN genres TEXT")
                connection.execSQL("ALTER TABLE artists ADD COLUMN spotifyUrl TEXT")
            }
        }

        /** Adds artist artwork for map pins. Nullable, so existing shows survive untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE artists ADD COLUMN imageUrl TEXT")
            }
        }
    }
}
