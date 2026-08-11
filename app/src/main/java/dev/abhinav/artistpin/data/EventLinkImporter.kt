package dev.abhinav.artistpin.data

import android.util.Log
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.OffsetDateTime

/** Everything a ticket page can tell us about a show, in the shape the form already speaks. */
data class ImportedEvent(
    val title: String,
    val date: LocalDate?,
    val artists: List<ImportedArtist>,
    val venueName: String,
    val city: String,
    val region: String,
    val country: String,
    val latitude: Double?,
    val longitude: Double?,
    val address: String?,
    val source: String,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

data class ImportedArtist(val name: String, val imageUrl: String?)

/**
 * Fills the form from a ticket link, so a show you already have a page for isn't typed out again.
 *
 * Deliberately narrow: a link either resolves to something we can trust or it doesn't. Nothing is
 * guessed from a page's prose, and nothing is saved — everything lands in the form for the user
 * to check.
 */
interface EventLinkImporter {
    /** True for links this importer knows how to read, so the UI can say so before trying. */
    fun canImport(url: String): Boolean

    suspend fun import(url: String): DataResult<ImportedEvent>
}

/**
 * Reads a DICE event.
 *
 * Two hops: the public page carries the internal event id in its app deep-link tag, and
 * `api.dice.fm/events/{id}` then answers with structured JSON — lineup, venue coordinates and the
 * local start time — with no key and no scraping of rendered markup. The page's own JSON-LD has
 * the venue but never the lineup, which is why the API is worth the second request.
 */
class DiceEventLinkImporter(
    private val client: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher,
) : EventLinkImporter {

    override fun canImport(url: String): Boolean =
        DICE_HOST.containsMatchIn(url.trim())

    override suspend fun import(url: String): DataResult<ImportedEvent> = withContext(ioDispatcher) {
        val link = url.trim()
        if (!canImport(link)) {
            return@withContext DataResult.Failure(DataError.Validation("That doesn't look like a DICE link"))
        }
        try {
            val page = get(link)
                ?: return@withContext DataResult.Failure(DataError.Network("Couldn't open that link"))
            // The deep-link tag is the reliable source, but a share link's own redirect chain
            // carries the id too, which covers a page that renders without the tag.
            val eventId = EVENT_ID.find(page)?.groupValues?.get(1)
                ?: return@withContext DataResult.Failure(
                    DataError.Validation("That DICE page doesn't look like an event"),
                )
            val body = get("$API_BASE$eventId")
                ?: return@withContext DataResult.Failure(DataError.Network("Couldn't reach DICE"))

            DataResult.Success(json.decodeFromString<DiceEvent>(body).toImported())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Import failed for '$link'", e)
            DataResult.Failure(DataError.Unknown("Couldn't read that event"))
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            // DICE serves the mobile site to anything that looks like a browser and blocks the
            // rest; the deep-link tag we need is only in the real page.
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private companion object {
        const val TAG = "EventLink"
        const val API_BASE = "https://api.dice.fm/events/"
        /**
         * Any DICE host, because the share sheet in their app hands out `link.dice.fm/…` Branch
         * links rather than event URLs. Those redirect — via app.link and the numeric event id —
         * to the same page, and OkHttp follows all of it.
         */
        val DICE_HOST = Regex("""https?://([a-z0-9-]+\.)?dice\.fm/""", RegexOption.IGNORE_CASE)

        /** The page's app deep link, e.g. `dice://open/events/69d006880e635b0001d52255`. */
        val EVENT_ID = Regex("""dice://open/events/([a-f0-9]{24})""")

        const val BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.4 Safari/605.1.15"
    }
}

// ---- The slice of DICE's response we actually read ----------------------------------------

@Serializable
internal data class DiceEvent(
    val name: String = "",
    val dates: DiceDates = DiceDates(),
    @SerialName("summary_lineup") val lineup: DiceLineup = DiceLineup(),
    val venues: List<DiceVenue> = emptyList(),
)

@Serializable
internal data class DiceDates(
    @SerialName("event_start_date") val start: String? = null,
)

@Serializable
internal data class DiceLineup(
    @SerialName("top_artists") val artists: List<DiceArtist> = emptyList(),
)

@Serializable
internal data class DiceArtist(
    val name: String = "",
    val image: DiceImage? = null,
)

@Serializable
internal data class DiceImage(val url: String? = null)

@Serializable
internal data class DiceVenue(
    val name: String = "",
    val address: String? = null,
    val location: DiceLocation? = null,
    val city: DiceCity? = null,
)

@Serializable
internal data class DiceLocation(val lat: Double? = null, val lng: Double? = null)

@Serializable
internal data class DiceCity(
    val name: String = "",
    @SerialName("country_name") val country: String = "",
)

internal fun DiceEvent.toImported(): ImportedEvent {
    val venue = venues.firstOrNull()
    return ImportedEvent(
        title = name.trim(),
        // The start date carries its own offset, so a show that starts at 15:00 in New York is
        // dated in New York rather than wherever the phone happens to be.
        date = dates.start?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() },
        artists = lineup.artists
            .filter { it.name.isNotBlank() }
            .map { ImportedArtist(it.name.trim(), it.image?.url?.takeIf { url -> url.isNotBlank() }) },
        venueName = venue?.name.orEmpty(),
        city = venue?.city?.name.orEmpty(),
        region = venue?.address?.let(::stateFrom).orEmpty(),
        country = venue?.city?.country.orEmpty(),
        latitude = venue?.location?.lat,
        longitude = venue?.location?.lng,
        address = venue?.address,
        source = "DICE",
    )
}

/**
 * DICE gives a city and a country but no state, so it comes out of the postal line —
 * "52-19 Flushing Ave, Maspeth, NY 11378, USA". No match just means no state, which is the
 * common case outside the US anyway.
 */
private val US_STATE = Regex("""\b([A-Z]{2})\s+\d{5}""")

internal fun stateFrom(address: String): String = US_STATE.find(address)?.groupValues?.get(1).orEmpty()
