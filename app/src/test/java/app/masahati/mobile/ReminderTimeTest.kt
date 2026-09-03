package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test
    fun weeklyReminderMovesToNextRequestedWeekday() {
        val now = ZonedDateTime.of(2026, 9, 3, 19, 0, 0, 0, zone)
        val epoch = ReminderTime.nextWeekly(now, DayOfWeek.MONDAY.value, 18, 30)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), zone)
        assertEquals(2026, actual.year)
        assertEquals(9, actual.monthValue)
        assertEquals(7, actual.dayOfMonth)
        assertEquals(18, actual.hour)
        assertEquals(30, actual.minute)
    }

    @Test
    fun dailyReminderMovesToTomorrowWhenTodaysTimePassed() {
        val now = ZonedDateTime.of(2026, 9, 3, 19, 0, 0, 0, zone)
        val epoch = ReminderTime.nextDaily(now, 18, 30)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), zone)
        assertEquals(4, actual.dayOfMonth)
        assertEquals(18, actual.hour)
        assertEquals(30, actual.minute)
    }

    @Test
    fun parsesArabicColloquialWeekdayAndClock() {
        assertEquals(DayOfWeek.MONDAY.value, ReminderTime.parseWeekday("اثنين"))
        assertEquals(DayOfWeek.WEDNESDAY.value, ReminderTime.parseWeekday("اربعاء"))
        val clock = ReminderTime.parseClock("الساعة 18.30")
        assertNotNull(clock)
        assertEquals(18, clock!!.first)
        assertEquals(30, clock.second)
    }
}
