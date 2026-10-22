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
    version = 11,
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
         * Drops `notes` and `rating` from events.
         *
         * Both were carried through every layer — Room, the backup format, the sync payload and
         * Postgres — since the first schema, and no screen ever read or wrote either one. Nothing
         * is lost because nothing was ever stored: no version of this app had a field for them.
         *
         * Rebuilt rather than `ALTER TABLE ... DROP COLUMN`, which SQLite only learned in 3.35 —
         * newer than the SQLite on an API 28 device, which this app still supports.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS events_new (
                        id TEXT NOT NULL,
                        venueId TEXT NOT NULL,
                        dateEpochDay INTEGER NOT NULL,
                        title TEXT,
                        PRIMARY KEY(id),
                        FOREIGN KEY(venueId) REFERENCES venues(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "INSERT INTO events_new (id, venueId, dateEpochDay, title) " +
                        "SELECT id, venueId, dateEpochDay, title FROM events",
                )
                connection.execSQL("DROP TABLE events")
                connection.execSQL("ALTER TABLE events_new RENAME TO events")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_events_venueId ON events (venueId)")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_events_dateEpochDay ON events (dateEpochDay)",
                )
            }
        }

        /**
         * Clears the upload-failure count on every photo.
         *
         * Uploads could not have worked at all: the Storage plugin was never installed on the
         * Supabase client, so every attempt threw before reaching the network. Photos that burned
         * through their retry budget did so for a reason that had nothing to do with them, and
         * without this reset they would never be offered to the queue again.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE event_media SET uploadAttempts = 0")
            }
        }

        /**
         * Records how often a photo's upload has been abandoned, so one that can never be sent
         * stops being retried. Defaults to zero, which correctly describes every existing row.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE event_media ADD COLUMN uploadAttempts INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the object-storage path to media rows. Additive and nullable: every existing photo
         * starts with no remote copy, which is exactly true of them, and the backfill picks them
         * up from there.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE event_media ADD COLUMN remotePath TEXT")
            }
        }

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
