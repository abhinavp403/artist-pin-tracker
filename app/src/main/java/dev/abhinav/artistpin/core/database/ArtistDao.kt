package dev.abhinav.artistpin.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtistDao {

    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.imageUrl AS imageUrl,
               a.genres AS genres, a.spotifyUrl AS spotifyUrl,
               COUNT(DISTINCT e.id) AS timesSeen,
               (SELECT GROUP_CONCAT(DISTINCT c2.name)
                  FROM event_artists ea2
                  JOIN events e2 ON e2.id = ea2.eventId
                  JOIN venues v2 ON v2.id = e2.venueId
                  JOIN cities c2 ON c2.id = v2.cityId
                 WHERE ea2.artistId = a.id) AS cityNames,
               MIN(e.dateEpochDay) AS firstSeenEpochDay,
               MAX(e.dateEpochDay) AS lastSeenEpochDay
          FROM artists a
          JOIN event_artists ea ON ea.artistId = a.id
          JOIN events e ON e.id = ea.eventId
         GROUP BY a.id
         ORDER BY timesSeen DESC, a.name ASC
        """,
    )
    fun observeArtistSummaries(): Flow<List<ArtistSummaryRow>>

    @Query("SELECT * FROM artists ORDER BY name ASC")
    fun observeAllArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artists WHERE id = :artistId")
    fun observeArtist(artistId: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM artists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ArtistEntity?

    /**
     * A null genres column means "never looked up" — a lookup that finds nothing writes an empty
     * string instead, so artists with genuinely no data aren't re-queried on every launch.
     */
    @Query("SELECT * FROM artists WHERE genres IS NULL")
    fun observeArtistsWithoutProfile(): Flow<List<ArtistEntity>>

    @Query(
        """
        UPDATE artists
           SET imageUrl = COALESCE(:imageUrl, imageUrl),
               genres = :genres,
               spotifyUrl = COALESCE(:spotifyUrl, spotifyUrl)
         WHERE id = :artistId
        """,
    )
    suspend fun setProfile(artistId: String, imageUrl: String?, genres: String, spotifyUrl: String?)

    @Query("SELECT * FROM artists")
    suspend fun allArtists(): List<ArtistEntity>

    @Query("DELETE FROM artists")
    suspend fun deleteAllArtists()

    @Upsert
    suspend fun upsertArtists(artists: List<ArtistEntity>)

    /** Artists exist only through the shows they were on. */
    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM event_artists)")
    suspend fun deleteOrphanArtists()

    /**
     * Clears the whole cached profile, not just the artwork: a rename means the row now stands
     * for a different artist, so its portrait, genres and Spotify link all belonged to the old
     * name. Nulling genres is also what puts the row back in [observeArtistsWithoutProfile] —
     * clearing the image alone left it blank forever, with no lookup ever queued to refill it.
     */
    @Query(
        """
        UPDATE artists
           SET name = :name, imageUrl = NULL, genres = NULL, spotifyUrl = NULL
         WHERE id = :artistId
        """,
    )
    suspend fun rename(artistId: String, name: String)

    @Query("DELETE FROM artists WHERE id = :artistId")
    suspend fun deleteArtist(artistId: String)

    /** Repoints an artist's shows onto another row, used when a rename collides with an existing artist. */
    @Query("UPDATE OR REPLACE event_artists SET artistId = :targetId WHERE artistId = :sourceId")
    suspend fun reassignEvents(sourceId: String, targetId: String)

    @Query("SELECT COUNT(*) FROM event_artists WHERE artistId = :artistId")
    suspend fun eventCountFor(artistId: String): Int

    /** Shows that would be left with nobody on the bill once this artist is gone. */
    @Query(
        """
        SELECT COUNT(*) FROM events e
         WHERE EXISTS (SELECT 1 FROM event_artists WHERE eventId = e.id AND artistId = :artistId)
           AND NOT EXISTS (SELECT 1 FROM event_artists WHERE eventId = e.id AND artistId != :artistId)
        """,
    )
    suspend fun eventsLeftEmptyBy(artistId: String): Int

    @Query(
        """
        DELETE FROM events WHERE id IN (
            SELECT e.id FROM events e
             WHERE NOT EXISTS (SELECT 1 FROM event_artists WHERE eventId = e.id)
        )
        """,
    )
    suspend fun deleteEventsWithoutArtists()

}
