package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderContextResolverTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 9, 6, 8, 30, 0, 0, zone)

    @Test
    fun resolvesOldConversationScenarioFromPreviousSchedule() {
        val result = ReminderContextResolver.resolve(
            currentText = "بدي تذكرني فيها كل اثنين",
            recentUserTexts = listOf("كل يوم اثنين الساعة 18.30"),
            now = now
        )
        assertNotNull(result)
        assertTrue(result!!.resolution.ready)
        assertEquals("weekly", result.resolution.repeatRule)
        assertEquals(DayOfWeek.MONDAY.value, result.resolution.dayOfWeek)
        assertEquals(18, result.resolution.hour)
        assertEquals(30, result.resolution.minute)
    }

    @Test
    fun directCompleteReminderWinsOverOldContext() {
        val result = ReminderContextResolver.resolve(
            currentText = "ذكرني بكرا الساعة 17:30",
            recentUserTexts = listOf("الجمعة الساعة 10:00"),
            now = now
        )
        assertNotNull(result)
        assertTrue(result!!.resolution.ready)
        assertEquals(17, result.resolution.hour)
        assertEquals(30, result.resolution.minute)
        assertEquals("ذكرني بكرا الساعة 17:30", result.sourceText)
    }

    @Test
    fun contextualPronounCanReuseAppointmentTime() {
        val result = ReminderContextResolver.resolve(
            currentText = "ذكرني فيها",
            recentUserTexts = listOf("موعد الطبيب الثلاثاء الساعة 16:20"),
            now = now
        )
        assertNotNull(result)
        assertTrue(result!!.resolution.ready)
        assertEquals(DayOfWeek.TUESDAY.value, result.resolution.dayOfWeek)
        assertEquals(16, result.resolution.hour)
        assertEquals(20, result.resolution.minute)
    }

    @Test
    fun plainIncompleteReminderDoesNotStealOldUnrelatedSchedule() {
        val result = ReminderContextResolver.resolve(
            currentText = "ذكرني اشتري خبز",
            recentUserTexts = listOf("دوامي الاثنين الساعة 18:30"),
            now = now
        )
        assertNotNull(result)
        assertFalse(result!!.resolution.ready)
        assertEquals("ذكرني اشتري خبز", result.sourceText)
    }
}
