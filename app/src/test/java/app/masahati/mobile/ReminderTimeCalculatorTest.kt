package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeCalculatorTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun nextMondayAt1830FromThursday() {
        val now = ZonedDateTime.of(2026, 9, 3, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        val trigger = ReminderTimeCalculator.nextTriggerMillis(now, zone, null, "الاثنين", "18:30", "كل الاثنين")
        assertNotNull(trigger)
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(trigger!!), zone)
        assertEquals(2026, z.year)
        assertEquals(9, z.monthValue)
        assertEquals(7, z.dayOfMonth)
        assertEquals(18, z.hour)
        assertEquals(30, z.minute)
    }

    @Test
    fun tomorrowDateResolvesCorrectly() {
        val now = ZonedDateTime.of(2026, 9, 3, 22, 0, 0, 0, zone).toInstant().toEpochMilli()
        val trigger = ReminderTimeCalculator.nextTriggerMillis(now, zone, "غداً", null, "08:15", null)!!
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(trigger), zone)
        assertEquals(4, z.dayOfMonth)
        assertEquals(8, z.hour)
        assertEquals(15, z.minute)
    }

    @Test
    fun weeklyReminderAdvancesSevenDays() {
        val current = ZonedDateTime.of(2026, 9, 7, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
        val next = ReminderTimeCalculator.nextAfterFire(current, zone, "كل الاثنين")!!
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), zone)
        assertEquals(14, z.dayOfMonth)
        assertEquals(18, z.hour)
        assertEquals(30, z.minute)
    }
}
