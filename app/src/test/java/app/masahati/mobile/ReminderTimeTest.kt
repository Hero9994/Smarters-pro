package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun relativeReminderKeepsFullDelay() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 35, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني بعد ساعتين", now)!!
        assertTrue(parsed.ready)
        assertEquals(120, parsed.delayMinutes)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed.triggerAt!!), zone)
        assertEquals(18, actual.hour)
        assertEquals(47, actual.minute)
        assertEquals(35, actual.second)
    }

    @Test
    fun tomorrowWith24HourTimeCreatesOneTimeReminder() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني بكرا الساعة 17:30", now)!!
        assertTrue(parsed.ready)
        assertEquals("none", parsed.repeatRule)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed.triggerAt!!), zone)
        assertEquals(5, actual.dayOfMonth)
        assertEquals(17, actual.hour)
        assertEquals(30, actual.minute)
    }

    @Test
    fun ambiguousTomorrowHourAsksMorningOrEvening() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني بكرا الساعة 5", now)!!
        assertFalse(parsed.ready)
        assertTrue(parsed.clarification!!.contains("صباح"))
    }

    @Test
    fun weekdayWithoutEveryIsOneTimeNotWeekly() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني يوم الاثنين الساعة 18:30", now)!!
        assertTrue(parsed.ready)
        assertEquals("none", parsed.repeatRule)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed.triggerAt!!), zone)
        assertEquals(DayOfWeek.MONDAY, actual.dayOfWeek)
        assertEquals(7, actual.dayOfMonth)
    }

    @Test
    fun everyWeekdayCreatesWeeklyReminder() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني كل اثنين الساعة 18:30", now)!!
        assertTrue(parsed.ready)
        assertEquals("weekly", parsed.repeatRule)
        assertEquals(DayOfWeek.MONDAY.value, parsed.dayOfWeek)
    }

    @Test
    fun todayAmbiguousFiveResolvesToNextFivePm() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني اليوم الساعة 5", now)!!
        assertTrue(parsed.ready)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed.triggerAt!!), zone)
        assertEquals(17, actual.hour)
    }

    @Test
    fun arabicIndicDigitsAreUnderstood() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("ذكرني بكرا الساعة ١٧:٣٠", now)!!
        assertTrue(parsed.ready)
        val actual = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed.triggerAt!!), zone)
        assertEquals(17, actual.hour)
        assertEquals(30, actual.minute)
    }

    @Test
    fun dateWithoutClockDoesNotInventATime() {
        val now = ZonedDateTime.of(2026, 9, 4, 16, 47, 0, 0, zone)
        val parsed = NaturalReminderParser.parse("اعمل تذكير بتاريخ 05.09.2026", now)!!
        assertFalse(parsed.ready)
        assertEquals("أي ساعة تريد التذكير؟", parsed.clarification)
    }
    @Test
    fun deliveredReminderLooksLikeAChatMessage() {
        val fire = ZonedDateTime.of(2026, 9, 4, 20, 30, 0, 0, zone)
        val now = ZonedDateTime.of(2026, 9, 4, 20, 30, 0, 0, zone)
        val reminder = ReminderRow(
            id = 1L,
            spaceId = 7L,
            title = "تذكير مساحاتي",
            body = "ذكرني اعبي ديزل اليوم الساعة 20.30",
            repeatRule = "none",
            dayOfWeek = null,
            hour = 20,
            minute = 30,
            nextFireAt = fire.toInstant().toEpochMilli(),
            enabled = true,
            deliveredAt = null,
            createdAt = now.minusHours(2).toInstant().toEpochMilli()
        )
        assertEquals(
            "تذكير: اليوم الساعة 20.30 اعبي ديزل",
            ReminderDeliveryText.build(reminder, zone, now.toInstant().toEpochMilli())
        )
    }

    @Test
    fun deliveredReminderStripsRecurringTimeWordsFromTask() {
        assertEquals(
            "اخد الدواء",
            ReminderDeliveryText.cleanTask("ذكرني كل اثنين الساعة 18:30 اخد الدواء")
        )
    }

}
