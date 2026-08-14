package dev.abhinav.artistpin.data

import dev.abhinav.artistpin.core.model.BackupData
import dev.abhinav.artistpin.core.model.DataError
import dev.abhinav.artistpin.core.model.DataResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend backup path is the escape hatch once the data no longer lives on the device, so what
 * matters is that a file written by either implementation is readable by the other.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BackendBackupRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = FakeBackendApi()
    private val backup = BackendBackupRepository(api, json, testDispatcher)

    /** Exactly what `export_backup()` emits, in the app's on-disk format. */
    private val serverPayload = json.parseToJsonElement(
        """
        {
          "version": 1,
          "exportedAtEpochMillis": 1750000000000,
          "cities": [{"id":"c1","name":"Queens","country":"United States","region":"NY"}],
          "venues": [{"id":"v1","name":"Knockdown Center","cityId":"c1",
                      "latitude":40.71,"longitude":-73.92,"address":null}],
          "artists": [{"id":"a1","name":"Bedouin","imageUrl":null,
                       "genres":"melodic house","spotifyUrl":null}],
          "events": [{"id":"e1","venueId":"v1","dateEpochDay":20253,
                      "title":null,"notes":null,"rating":5}],
          "eventArtists": [{"eventId":"e1","artistId":"a1","billing":"HEADLINER"}],
          "media": []
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `an exported file parses back through the app's own backup format`() =
        runTest(testDispatcher) {
            api.exportPayload = serverPayload

            val contents = (backup.export() as DataResult.Success).data
            val parsed = (backup.parse(contents) as DataResult.Success).data

            // The round trip is the whole point: a file written on the backend build has to be a
            // valid Artist Pin backup, not a backend-flavoured near-miss.
            assertEquals(1, parsed.events.size)
            assertEquals(1, parsed.summary.shows)
            assertEquals("Knockdown Center", parsed.venues.single().name)
            assertEquals(20253L, parsed.events.single().dateEpochDay)
            assertEquals(5, parsed.events.single().rating)
        }

    @Test
    fun `restore sends the file back as a JSON object, not a string`() = runTest(testDispatcher) {
        val parsed = (backup.parse(json.encodeToString(serverPayload)) as DataResult.Success).data

        backup.restore(parsed)

        // import_backup takes jsonb and walks `payload -> 'events'`. An encoded string would nest
        // the library in one scalar and import nothing while reporting success.
        val sent = api.importedPayload!!
        assertEquals(1, sent["events"]!!.jsonArray.size)
        assertEquals("e1", sent["events"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `restore merges rather than replacing`() {
        // The dialog reads this to decide whether to promise a replacement or a repair. Getting it
        // backwards would either lie to the user or wipe a library they expected to be topped up.
        // The Room implementation's opposite answer is asserted in RoomBackupRepositoryTest.
        assertFalse(backup.restoreReplaces)
    }

    @Test
    fun `a backup made by a newer version is refused rather than half-read`() =
        runTest(testDispatcher) {
            val future = """{"version": 99, "events": []}"""

            val result = backup.parse(future)

            assertTrue(result is DataResult.Failure)
            assertTrue((result as DataResult.Failure).error is DataError.Validation)
        }

    @Test
    fun `a dropped connection during export is a network failure, not a corrupt file`() =
        runTest(testDispatcher) {
            api.failNextRead = FakeBackendApi.offline()

            val result = backup.export()

            // Telling the user their backup file is invalid when the wifi dropped would send them
            // looking in entirely the wrong place.
            assertTrue(result is DataResult.Failure)
            assertTrue((result as DataResult.Failure).error is DataError.Network)
        }
}
