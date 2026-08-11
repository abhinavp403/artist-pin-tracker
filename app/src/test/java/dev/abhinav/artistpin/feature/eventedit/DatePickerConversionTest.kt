package dev.abhinav.artistpin.feature.eventedit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * Regression: the two halves of the date picker used different zones, so east of Greenwich it
 * opened on the day before the one being edited — and confirming without touching it saved the
 * show a day early.
 */
class DatePickerConversionTest {

    private val original: TimeZone = TimeZone.getDefault()

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun `a date survives the round trip in every timezone`() {
        val date = LocalDate.of(2026, 8, 7)

        listOf("Asia/Kolkata", "Pacific/Kiritimati", "America/New_York", "Pacific/Honolulu", "UTC")
            .forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("round trip in $zone", date, pickedDate(date.toPickerMillis()))
            }
    }

    /** Material hands back midnight UTC for the day tapped; that is the day we must store. */
    @Test
    fun `midnight UTC is read as that day, not the one either side of it`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))

        val millis = LocalDate.of(2026, 3, 28).toPickerMillis()

        assertEquals(LocalDate.of(2026, 3, 28), pickedDate(millis))
        assertEquals(0L, millis % 86_400_000L)
    }
}
