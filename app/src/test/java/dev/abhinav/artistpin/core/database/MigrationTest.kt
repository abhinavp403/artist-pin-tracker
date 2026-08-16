package dev.abhinav.artistpin.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * imageUrl shipped after real shows already existed on device, so the 1→2 migration has to
 * preserve them rather than fall back to a destructive rebuild.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ArtistPinDatabase::class.java,
    )

    @Test
    fun `migrating to version 2 keeps existing artists and adds a null imageUrl`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO artists (id, name) VALUES ('a1', 'The Weeknd')")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            ArtistPinDatabase.MIGRATION_1_2,
        )

        db.query("SELECT name, imageUrl FROM artists WHERE id = 'a1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("The Weeknd", cursor.getString(0))
            assertTrue("imageUrl should start empty", cursor.isNull(1))
        }
        db.close()
    }

    /** 80 artists with artwork already exist on device; genres must land as "never looked up". */
    @Test
    fun `migrating to version 3 keeps artwork and leaves genres unfetched`() {
        helper.createDatabase(TEST_DB_3, 2).apply {
            execSQL(
                "INSERT INTO artists (id, name, imageUrl) " +
                    "VALUES ('a1', 'Boris Brejcha', 'https://img/boris.jpg')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_3,
            3,
            true,
            ArtistPinDatabase.MIGRATION_2_3,
        )

        db.query("SELECT name, imageUrl, genres, spotifyUrl FROM artists WHERE id = 'a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Boris Brejcha", c.getString(0))
            assertEquals("https://img/boris.jpg", c.getString(1))
            assertTrue("genres null means the backfill will pick it up", c.isNull(2))
            assertTrue(c.isNull(3))
        }
        db.close()
    }

    /** Spotify had marked every artist "looked up, no genres"; v4 re-arms the backfill. */
    @Test
    fun `migrating to version 4 clears genres but keeps artwork and links`() {
        helper.createDatabase(TEST_DB_4, 3).apply {
            execSQL(
                "INSERT INTO artists (id, name, imageUrl, genres, spotifyUrl) VALUES " +
                    "('a1', 'Boris Brejcha', 'https://img/boris.jpg', '', 'https://open.spotify.com/x')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_4,
            4,
            true,
            ArtistPinDatabase.MIGRATION_3_4,
        )

        db.query("SELECT imageUrl, genres, spotifyUrl FROM artists WHERE id = 'a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("https://img/boris.jpg", c.getString(0))
            assertTrue("genres cleared so the backfill runs again", c.isNull(1))
            assertEquals("https://open.spotify.com/x", c.getString(2))
        }
        db.close()
    }

    /** Dixon and Bedouin were resolved to the wrong artists; only those two need re-fetching. */
    @Test
    fun `migrating to version 5 clears the misidentified artists and leaves the rest alone`() {
        helper.createDatabase(TEST_DB_5, 4).apply {
            execSQL(
                "INSERT INTO artists (id, name, imageUrl, genres, spotifyUrl) VALUES " +
                    "('a1', 'Dixon', 'https://img/wrong.jpg', 'country', 'https://open.spotify.com/wrong')," +
                    "('a2', 'Bedouin', 'https://img/wrong2.jpg', 'blues', 'https://open.spotify.com/wrong2')," +
                    "('a3', 'Boris Brejcha', 'https://img/boris.jpg', 'techno', 'https://open.spotify.com/x')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_5, 5, true, ArtistPinDatabase.MIGRATION_4_5)

        db.query("SELECT name, imageUrl, genres, spotifyUrl FROM artists ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("Dixon's wrong artwork is cleared", c.isNull(1))
            assertTrue("and re-armed for the backfill", c.isNull(2))
            assertTrue("and its wrong link is gone", c.isNull(3))

            assertTrue(c.moveToNext())
            assertTrue("Bedouin's wrong artwork is cleared", c.isNull(1))
            assertTrue(c.isNull(2))
            assertTrue(c.isNull(3))

            assertTrue(c.moveToNext())
            assertEquals("Boris Brejcha", c.getString(0))
            assertEquals("everyone else is untouched", "https://img/boris.jpg", c.getString(1))
            assertEquals("techno", c.getString(2))
        }
        db.close()
    }

    /** Renamed artists were stranded with no artwork and no lookup queued to refill it. */
    @Test
    fun `migrating to version 6 re-queues artists left without artwork`() {
        helper.createDatabase(TEST_DB_6, 5).apply {
            execSQL(
                "INSERT INTO artists (id, name, imageUrl, genres, spotifyUrl) VALUES " +
                    "('a1', 'NGHTMRE', NULL, 'dubstep', 'https://open.spotify.com/old')," +
                    "('a2', 'Boris Brejcha', 'https://img/boris.jpg', 'techno', 'https://open.spotify.com/x')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB_6, 6, true, ArtistPinDatabase.MIGRATION_5_6)

        db.query("SELECT name, genres, spotifyUrl FROM artists ORDER BY id").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("the renamed artist is queued for lookup again", c.isNull(1))
            assertTrue("and its old profile link is dropped", c.isNull(2))

            assertTrue(c.moveToNext())
            assertEquals("Boris Brejcha", c.getString(0))
            assertEquals("an artist with artwork is untouched", "techno", c.getString(1))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val TEST_DB_7 = "migration-test-7.db"
        const val TEST_DB_6 = "migration-test-6.db"
        const val TEST_DB_5 = "migration-test-5.db"
        const val TEST_DB_4 = "migration-test-4.db"
        const val TEST_DB_3 = "migration-test-3.db"
    }

    /**
     * The sync queue lands on databases that already hold a full library. It is purely additive, so
     * the shows have to survive untouched — a destructive fallback here would wipe every concert on
     * the device to add an empty table.
     */
    @Test
    fun `migrating to version 7 adds an empty outbox and keeps the library`() {
        helper.createDatabase(TEST_DB_7, 6).apply {
            execSQL("INSERT INTO cities (id, name, country) VALUES ('c1', 'Queens', 'US')")
            execSQL(
                "INSERT INTO venues (id, name, cityId, latitude, longitude) " +
                    "VALUES ('v1', 'Knockdown Center', 'c1', 40.71, -73.92)",
            )
            execSQL(
                "INSERT INTO events (id, venueId, dateEpochDay) VALUES ('e1', 'v1', 20253)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB_7,
            7,
            true,
            ArtistPinDatabase.MIGRATION_6_7,
        )

        db.query("SELECT COUNT(*) FROM events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        // An upgrading device is already in step with the backend, so starting with anything
        // queued would replay changes the server has had for months.
        db.query("SELECT COUNT(*) FROM sync_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }
}
