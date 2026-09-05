package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ConditionalReminderParserTest {
    private val now = ZonedDateTime.of(2026, 9, 6, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))

    @Test
    fun parsesArabicConditionalReminder() {
        val r = ConditionalReminderParser.parse("ذكرني إذا ما دفعت الفاتورة خلال 5 أيام", now)!!
        assertTrue(r.ready)
        assertEquals("دفعت الفاتورة", r.task)
        assertNotNull(r.dueAt)
        assertEquals(5L * 24L * 60L * 60L * 1000L, r.dueAt!! - now.toInstant().toEpochMilli())
    }

    @Test
    fun arabicIndicDigitsWork() {
        val r = ConditionalReminderParser.parse("ذكرني اذا ما رديت على الرسالة خلال ٣ أيام", now)!!
        assertTrue(r.ready)
        assertEquals("رديت على الرسالة", r.task)
    }

    @Test
    fun missingDurationAsksInsteadOfGuessing() {
        val r = ConditionalReminderParser.parse("ذكرني إذا ما دفعت الفاتورة", now)!!
        assertFalse(r.ready)
        assertTrue(r.clarification!!.contains("كم وقت"))
    }

    @Test
    fun normalReminderIsNotConditional() {
        assertEquals(null, ConditionalReminderParser.parse("ذكرني بكرا الساعة 5", now))
    }
}
