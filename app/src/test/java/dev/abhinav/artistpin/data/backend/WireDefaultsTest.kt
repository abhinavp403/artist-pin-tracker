package dev.abhinav.artistpin.data.backend

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire shape, not the parsing.
 *
 * PostgREST derives the columns it will write from the keys we send. A field omitted because it
 * equals its default is a column the server inserts NULL into — which is how sort_index, a NOT NULL
 * column, ended up crashing an insert.
 */
class WireDefaultsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `a media row sends every column even at default values`() {
        val encoded = json.encodeToString(
            EventMediaDto.serializer(),
            EventMediaDto(
                id = "m1",
                eventId = "e1",
                originalUri = "content://media/m1",
                mimeType = "image/jpeg",
                // sortIndex left at its default of 0 — the case that broke.
            ),
        )

        assertTrue("sort_index missing from $encoded", encoded.contains("\"sort_index\""))
        assertTrue(encoded.contains("\"storage_path\""))
    }

    @Test
    fun `save_event params send their optional arguments explicitly`() {
        val encoded = json.encodeToString(
            SaveEventParams.serializer(),
            SaveEventParams(
                eventDate = "2025-06-14",
                cityName = "Queens",
                country = "United States",
                venueName = "Knockdown Center",
                latitude = 40.71,
                longitude = -73.92,
                artistNames = listOf("Bedouin"),
                // supportNames left at its default: the RPC has no default for it server-side, so
                // omitting it means PostgREST cannot match the function signature at all.
            ),
        )

        assertTrue("p_support_names missing from $encoded", encoded.contains("\"p_support_names\""))
        assertTrue(encoded.contains("\"p_event_id\""))
    }
}
