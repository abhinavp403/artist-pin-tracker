package dev.abhinav.artistpin.data

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The payload shape is copied from a live DICE response (the Josh Baker show at Knockdown
 * Center), trimmed to the fields we read. Their JSON carries far more than this, so the parse
 * has to ignore what it doesn't know rather than fail on it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiceEventLinkImporterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val payload = """
        {
          "id": "69d006880e635b0001d52255",
          "name": "JOSH BAKER: DAY AND NIGHT",
          "perm_name": "6dlgq3-josh-baker-day-and-night",
          "date_unix": 1786215600,
          "dates": {
            "timezone": "America/New_York",
            "event_start_date": "2026-08-08T15:00:00-04:00",
            "event_end_date": "2026-08-09T04:00:00-04:00"
          },
          "summary_lineup": {
            "top_artists": [
              { "name": "Josh Baker", "image": { "url": "https://dice-media/josh.jpg" }, "is_headliner": false },
              { "name": "Laidlaw", "image": { "url": "https://dice-media/laidlaw.jpg" } },
              { "name": "Rana Iravani", "image": { "url": "" } }
            ]
          },
          "venues": [
            {
              "id": "2421",
              "name": "Knockdown Center",
              "address": "52-19 Flushing Ave, Maspeth, NY 11378, USA",
              "location": { "lat": 40.71496339999999, "lng": -73.9134793 },
              "city": { "name": "New York", "country_name": "United States of America" }
            }
          ],
          "price": { "total": 5816 }
        }
    """.trimIndent()

    private fun parse(body: String = payload) = json.decodeFromString<DiceEvent>(body).toImported()

    /**
     * The DICE app's share sheet hands out `link.dice.fm` Branch links, not event URLs — the
     * first thing anyone will actually paste.
     */
    @Test
    fun `share links and event links are both recognised`() {
        val importer = DiceEventLinkImporter(OkHttpClient(), json, UnconfinedTestDispatcher())

        assertTrue(importer.canImport("https://link.dice.fm/Q6u3ZROdl5b?sharer_id=641cf1df77"))
        assertTrue(importer.canImport("https://dice.fm/event/6dlgq3-josh-baker-tickets"))
        assertTrue(importer.canImport("https://www.dice.fm/event/6dlgq3"))
        assertTrue(importer.canImport("  https://dice.fm/event/6dlgq3  "))
        assertFalse(importer.canImport("https://ra.co/events/2137361"))
        assertFalse(importer.canImport("https://notdice.fm/event/1"))
    }

    @Test
    fun `a live payload maps onto the form's fields`() {
        val event = parse()

        assertEquals("JOSH BAKER: DAY AND NIGHT", event.title)
        assertEquals(LocalDate.of(2026, 8, 8), event.date)
        assertEquals("Knockdown Center", event.venueName)
        assertEquals("New York", event.city)
        assertEquals("United States of America", event.country)
        assertEquals(40.71496339999999, event.latitude!!, 0.000001)
        assertEquals(-73.9134793, event.longitude!!, 0.000001)
        assertTrue(event.hasLocation)
    }

    /** The bill arrives in running order; the first name is the one the night is named after. */
    @Test
    fun `the lineup keeps its order and its artwork`() {
        val event = parse()

        assertEquals(listOf("Josh Baker", "Laidlaw", "Rana Iravani"), event.artists.map { it.name })
        assertEquals("https://dice-media/josh.jpg", event.artists.first().imageUrl)
        assertNull("an empty image url is no image at all", event.artists.last().imageUrl)
    }

    /**
     * A 15:00 start in New York is an 8 August show wherever the phone is — the offset in the
     * timestamp is what decides, not the device's clock.
     */
    @Test
    fun `the date comes from the venue's own offset`() {
        val late = payload.replace("2026-08-08T15:00:00-04:00", "2026-08-08T23:30:00-04:00")

        assertEquals(LocalDate.of(2026, 8, 8), parse(late).date)
    }

    @Test
    fun `the state is read off the postal line`() {
        assertEquals("NY", parse().region)
        assertEquals("", stateFrom("Herbert-Baum-Straße 11, 10247 Berlin, Germany"))
    }

    /** A festival with no coordinates must not silently produce an unsaveable show. */
    @Test
    fun `a venue without coordinates reports no location`() {
        val noGeo = payload.replace("""{ "lat": 40.71496339999999, "lng": -73.9134793 }""", "null")

        val event = parse(noGeo)

        assertNull(event.latitude)
        assertEquals("Knockdown Center", event.venueName)
    }

    @Test
    fun `fields DICE stops sending do not break the parse`() {
        val sparse = """{ "name": "Warehouse Party" }"""

        val event = parse(sparse)

        assertEquals("Warehouse Party", event.title)
        assertNull(event.date)
        assertTrue(event.artists.isEmpty())
        assertEquals("", event.venueName)
    }
}
