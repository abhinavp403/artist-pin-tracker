package dev.abhinav.artistpin.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val EVENT_SUMMARY_SELECT = """
    SELECT e.id AS id,
           e.dateEpochDay AS dateEpochDay,
           e.title AS title,
           e.rating AS rating,
           v.id AS venueId,
           v.name AS venueName,
           c.name AS cityName,
           (SELECT GROUP_CONCAT(a.name, '||')
              FROM event_artists ea JOIN artists a ON a.id = ea.artistId
             WHERE ea.eventId = e.id AND ea.billing = 'HEADLINER') AS headlinerNames,
           (SELECT GROUP_CONCAT(a.name, '||')
              FROM event_artists ea JOIN artists a ON a.id = ea.artistId
             WHERE ea.eventId = e.id AND ea.billing = 'SUPPORT') AS supportNames,
           (SELECT COUNT(*) FROM event_media m WHERE m.eventId = e.id) AS mediaCount,
           (SELECT m.localPath FROM event_media m WHERE m.eventId = e.id
             ORDER BY m.sortIndex LIMIT 1) AS thumbnailPath,
           -- The same row's object-storage path. Needed because a device that never held the file
           -- has a localPath pointing at another phone's storage, which is why list thumbnails
           -- were blank after signing in on a new device while the show screen showed them fine.
           (SELECT m.remotePath FROM event_media m WHERE m.eventId = e.id
             ORDER BY m.sortIndex LIMIT 1) AS thumbnailRemotePath
      FROM events e
      JOIN venues v ON v.id = e.venueId
      JOIN cities c ON c.id = v.cityId
"""

/**
 * The pin shown once zoomed in is the headliner of the latest show at that venue, so the two
 * subqueries below both order by date descending and take one row.
 */
private const val VENUE_PIN_SELECT = """
    SELECT v.id AS id, v.name AS name, v.cityId AS cityId,
           v.latitude AS latitude, v.longitude AS longitude, v.address AS address,
           COUNT(e.id) AS eventCount,
           MAX(e.dateEpochDay) AS lastEventEpochDay,
           (SELECT a.name FROM events e2
              JOIN event_artists ea ON ea.eventId = e2.id AND ea.billing = 'HEADLINER'
              JOIN artists a ON a.id = ea.artistId
             WHERE e2.venueId = v.id
             ORDER BY e2.dateEpochDay DESC LIMIT 1) AS headlinerName,
           (SELECT a.imageUrl FROM events e2
              JOIN event_artists ea ON ea.eventId = e2.id AND ea.billing = 'HEADLINER'
              JOIN artists a ON a.id = ea.artistId
             WHERE e2.venueId = v.id
             ORDER BY e2.dateEpochDay DESC LIMIT 1) AS headlinerImageUrl
      FROM venues v
      JOIN events e ON e.venueId = v.id
"""

@Dao
interface ConcertDao {

    /**
     * World-map markers. Cities without events are excluded, and each marker sits at the
     * centroid of the venues actually visited there — which is why no geocoding is needed.
     */
    @Query(
        """
        SELECT c.id AS id, c.name AS name, c.country AS country, c.region AS region,
               centroid.latitude AS latitude,
               centroid.longitude AS longitude,
               COUNT(DISTINCT e.id) AS eventCount,
               COUNT(DISTINCT v.id) AS venueCount,
               MAX(e.dateEpochDay) AS lastEventEpochDay
          FROM cities c
          JOIN (
                SELECT v2.cityId AS cityId,
                       AVG(v2.latitude) AS latitude,
                       AVG(v2.longitude) AS longitude
                  FROM venues v2
                 WHERE EXISTS (SELECT 1 FROM events e2 WHERE e2.venueId = v2.id)
                 GROUP BY v2.cityId
               ) centroid ON centroid.cityId = c.id
          JOIN venues v ON v.cityId = c.id
          JOIN events e ON e.venueId = v.id
         GROUP BY c.id
         ORDER BY eventCount DESC, c.name ASC
        """,
    )
    fun observeCityPins(): Flow<List<CityPinRow>>

    @Query("$VENUE_PIN_SELECT WHERE v.cityId = :cityId GROUP BY v.id ORDER BY eventCount DESC, v.name ASC")
    fun observeVenuePins(cityId: String): Flow<List<VenuePinRow>>

    /** Every venue, for the world map's zoomed-in artist pins. */
    @Query("$VENUE_PIN_SELECT GROUP BY v.id ORDER BY eventCount DESC, v.name ASC")
    fun observeAllVenuePins(): Flow<List<VenuePinRow>>

    @Query("$EVENT_SUMMARY_SELECT WHERE e.venueId = :venueId ORDER BY e.dateEpochDay DESC")
    fun observeEventsAtVenue(venueId: String): Flow<List<EventSummaryRow>>

    @Query("$EVENT_SUMMARY_SELECT WHERE c.id = :cityId ORDER BY e.dateEpochDay DESC")
    fun observeEventsInCity(cityId: String): Flow<List<EventSummaryRow>>

    @Query("$EVENT_SUMMARY_SELECT ORDER BY e.dateEpochDay DESC")
    fun observeAllEvents(): Flow<List<EventSummaryRow>>

    @Query(
        """
        $EVENT_SUMMARY_SELECT
        WHERE e.id IN (SELECT eventId FROM event_artists WHERE artistId = :artistId)
        ORDER BY e.dateEpochDay DESC
        """,
    )
    fun observeEventsForArtist(artistId: String): Flow<List<EventSummaryRow>>

    @Transaction
    @Query("SELECT * FROM events WHERE id = :eventId")
    fun observeEventDetails(eventId: String): Flow<EventWithDetails?>

    @Query("SELECT * FROM cities ORDER BY name ASC")
    fun observeCities(): Flow<List<CityEntity>>

    @Query("SELECT * FROM cities WHERE id = :cityId")
    suspend fun getCity(cityId: String): CityEntity?

    @Query(
        "SELECT * FROM cities WHERE name = :name COLLATE NOCASE " +
            "AND country = :country COLLATE NOCASE LIMIT 1",
    )
    suspend fun findCity(name: String, country: String): CityEntity?

    @Query(
        "SELECT * FROM venues WHERE name = :name COLLATE NOCASE AND cityId = :cityId LIMIT 1",
    )
    suspend fun findVenue(name: String, cityId: String): VenueEntity?

    @Transaction
    @Query("SELECT * FROM venues ORDER BY name ASC")
    fun observeVenuesWithCity(): Flow<List<VenueWithCity>>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEvent(eventId: String): EventEntity?

    @Query("SELECT * FROM venues WHERE id = :venueId")
    suspend fun getVenue(venueId: String): VenueEntity?

    @Query(
        """
        SELECT ea.artistId AS artistId, ea.eventId AS eventId, ea.billing AS billing
          FROM event_artists ea WHERE ea.eventId = :eventId
        """,
    )
    suspend fun getEventArtistRefs(eventId: String): List<EventArtistCrossRef>

    @Query("SELECT * FROM cities")
    suspend fun allCities(): List<CityEntity>

    @Query("SELECT * FROM venues")
    suspend fun allVenues(): List<VenueEntity>

    @Query("SELECT * FROM events")
    suspend fun allEvents(): List<EventEntity>

    @Query("SELECT * FROM event_artists")
    suspend fun allEventArtists(): List<EventArtistCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCities(cities: List<CityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVenues(venues: List<VenueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<EventEntity>)

    /** Cascades through venues, events, media and cross-refs — the whole concert graph. */
    @Query("DELETE FROM cities")
    suspend fun deleteAllCities()

    @Upsert
    suspend fun upsertCity(city: CityEntity)

    @Upsert
    suspend fun upsertVenue(venue: VenueEntity)

    @Upsert
    suspend fun upsertEvent(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventArtists(refs: List<EventArtistCrossRef>)

    @Query("DELETE FROM event_artists WHERE eventId = :eventId")
    suspend fun clearEventArtists(eventId: String)

    @Delete
    suspend fun deleteEvent(event: EventEntity)

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEventById(eventId: String)

    /**
     * Cities and venues are only meaningful as somewhere you saw a show; once the last event
     * at them is gone they would otherwise linger as invisible orphan rows.
     */
    @Query("DELETE FROM venues WHERE id NOT IN (SELECT DISTINCT venueId FROM events)")
    suspend fun deleteOrphanVenues()

    @Query("DELETE FROM cities WHERE id NOT IN (SELECT DISTINCT cityId FROM venues)")
    suspend fun deleteOrphanCities()

    @Transaction
    suspend fun saveEvent(
        city: CityEntity,
        venue: VenueEntity,
        event: EventEntity,
        artistRefs: List<EventArtistCrossRef>,
    ) {
        upsertCity(city)
        upsertVenue(venue)
        upsertEvent(event)
        clearEventArtists(event.id)
        insertEventArtists(artistRefs)
    }

    @Transaction
    suspend fun deleteEventAndPrune(eventId: String) {
        deleteEventById(eventId)
        deleteOrphanVenues()
        deleteOrphanCities()
    }
}
