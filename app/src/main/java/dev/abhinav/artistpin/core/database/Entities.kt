package dev.abhinav.artistpin.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.abhinav.artistpin.core.model.Billing

@Entity(
    tableName = "cities",
    indices = [Index(value = ["name", "country"], unique = true)],
)
data class CityEntity(
    @PrimaryKey val id: String,
    val name: String,
    val country: String,
    val region: String? = null,
)

@Entity(
    tableName = "venues",
    foreignKeys = [
        ForeignKey(
            entity = CityEntity::class,
            parentColumns = ["id"],
            childColumns = ["cityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("cityId")],
)
data class VenueEntity(
    @PrimaryKey val id: String,
    val name: String,
    val cityId: String,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
)

@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"], unique = true)],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imageUrl: String? = null,
    /**
     * Delimited genre list, empty string once a lookup has run and found none. Null therefore
     * means "never looked up", which is what drives the backfill query.
     */
    val genres: String? = null,
    val spotifyUrl: String? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = VenueEntity::class,
            parentColumns = ["id"],
            childColumns = ["venueId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("venueId"), Index("dateEpochDay")],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val venueId: String,
    val dateEpochDay: Long,
    val title: String? = null,
    val notes: String? = null,
    val rating: Int? = null,
)

@Entity(
    tableName = "event_artists",
    primaryKeys = ["eventId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId"), Index("artistId")],
)
data class EventArtistCrossRef(
    val eventId: String,
    val artistId: String,
    val billing: Billing = Billing.HEADLINER,
)

@Entity(
    tableName = "event_media",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId")],
)
data class EventMediaEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val localPath: String,
    val originalUri: String,
    val mimeType: String,
    val capturedAt: Long? = null,
    val sortIndex: Int = 0,
    /**
     * Where the file lives in object storage, once it has got there. Null is the normal state for a
     * photo just added — it is the backlog the sync works through, and the reason a row can exist
     * while the bytes are still only on this phone.
     *
     * A path, not a URL: signed URLs expire, so storing one would mean storing something that stops
     * working.
     */
    val remotePath: String? = null,
    /**
     * How many times uploading this file has been abandoned.
     *
     * Exists because "has no remote path" is not the same as "should be uploaded". Without it the
     * backfill re-queues a photo that can never be sent on every single sync — and worse, it
     * regenerates the very entry the outbox just gave up on, defeating that escape hatch. Once this
     * reaches its limit the row is left alone: still shown, still yours, simply not retried.
     */
    val uploadAttempts: Int = 0,
)

/**
 * One local change waiting to reach the backend.
 *
 * The queue exists so a save never depends on the network: the write lands in Room, a row lands
 * here, and the screen returns. Whether the phone has signal is then somebody else's problem.
 *
 * Payloads carry *names*, not local ids, for anything in the shared catalog. A venue or artist
 * created offline has a Room id the server has never seen — the catalog is deduplicated server-side
 * by name, so the id cannot be guessed locally. Replaying by name lets `save_event` do its
 * find-or-create as though the show had been added online.
 */
@Entity(tableName = "sync_outbox", indices = [Index("entityId")])
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A [dev.abhinav.artistpin.data.sync.SyncOperation] name. */
    val operation: String,
    /** The event or artist this entry concerns, used to supersede earlier entries for the same thing. */
    val entityId: String,
    val payloadJson: String,
    val queuedAtEpochMillis: Long,
    /** Counted so a permanently poisonous entry can be spotted rather than retried forever. */
    val attempts: Int = 0,
    val lastError: String? = null,
)

data class VenueWithCity(
    @Embedded val venue: VenueEntity,
    @Relation(parentColumn = "cityId", entityColumn = "id") val city: CityEntity,
)

data class ArtistWithBilling(
    @Embedded val artist: ArtistEntity,
    val billing: Billing,
)

data class EventWithDetails(
    @Embedded val event: EventEntity,
    @Relation(entity = VenueEntity::class, parentColumn = "venueId", entityColumn = "id")
    val venue: VenueWithCity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = EventArtistCrossRef::class,
            parentColumn = "eventId",
            entityColumn = "artistId",
        ),
    )
    val artists: List<ArtistEntity>,
    @Relation(parentColumn = "id", entityColumn = "eventId")
    val media: List<EventMediaEntity>,
)

/** Aggregate row backing a world-map city marker. */
data class CityPinRow(
    @Embedded val city: CityEntity,
    val latitude: Double,
    val longitude: Double,
    val eventCount: Int,
    val venueCount: Int,
    val lastEventEpochDay: Long?,
)

/** Aggregate row backing a city-map venue marker. */
data class VenuePinRow(
    @Embedded val venue: VenueEntity,
    val eventCount: Int,
    val lastEventEpochDay: Long?,
    val headlinerName: String?,
    val headlinerImageUrl: String?,
)

data class EventSummaryRow(
    val id: String,
    val dateEpochDay: Long,
    val title: String?,
    val rating: Int?,
    val venueId: String,
    val venueName: String,
    val cityName: String,
    val headlinerNames: String?,
    val supportNames: String?,
    val mediaCount: Int,
    val thumbnailPath: String?,
)

data class ArtistSummaryRow(
    @Embedded val artist: ArtistEntity,
    val timesSeen: Int,
    val cityNames: String?,
    val firstSeenEpochDay: Long?,
    val lastSeenEpochDay: Long?,
)
