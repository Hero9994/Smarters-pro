package app.masahati.mobile

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

object ReminderTimeCalculator {
    fun nextTriggerMillis(
        nowMillis: Long,
        zoneId: ZoneId,
        dateText: String?,
        weekdayText: String?,
        timeText: String?,
        recurrence: String?
    ): Long? {
        val time = parseTime(timeText) ?: return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val date = resolveDate(now.toLocalDate(), dateText)
        if (date != null) {
            var candidate = ZonedDateTime.of(date, time, zoneId)
            if (!candidate.isAfter(now) && recurrence?.contains("كل يوم") == true) candidate = candidate.plusDays(1)
            return candidate.takeIf { it.isAfter(now) }?.toInstant()?.toEpochMilli()
        }

        val weekday = parseWeekday(weekdayText)
        if (weekday != null) {
            var candidateDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(weekday))
            var candidate = ZonedDateTime.of(candidateDate, time, zoneId)
            if (!candidate.isAfter(now)) {
                candidateDate = candidateDate.plusWeeks(1)
                candidate = ZonedDateTime.of(candidateDate, time, zoneId)
            }
            return candidate.toInstant().toEpochMilli()
        }

        if (recurrence?.contains("كل يوم") == true) {
            var candidate = ZonedDateTime.of(now.toLocalDate(), time, zoneId)
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
            return candidate.toInstant().toEpochMilli()
        }
        return null
    }

    fun nextAfterFire(currentMillis: Long, zoneId: ZoneId, recurrence: String?): Long? {
        val current = Instant.ofEpochMilli(currentMillis).atZone(zoneId)
        return when {
            recurrence?.contains("كل يوم") == true -> current.plusDays(1).toInstant().toEpochMilli()
            recurrence?.startsWith("كل ") == true || recurrence?.contains("أسبوع") == true -> current.plusWeeks(1).toInstant().toEpochMilli()
            else -> null
        }
    }

    private fun parseTime(value: String?): LocalTime? {
        val raw = value?.trim()?.replace('.', ':') ?: return null
        return try { LocalTime.parse(raw, DateTimeFormatter.ofPattern("H:mm")) } catch (_: DateTimeParseException) { null }
    }

    private fun resolveDate(today: LocalDate, value: String?): LocalDate? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (raw == "اليوم") return today
        if (raw == "غداً" || raw == "غدا") return today.plusDays(1)
        val clean = raw.removeSuffix(".")
        val parts = clean.split('.')
        return try {
            when (parts.size) {
                2 -> LocalDate.of(today.year, parts[1].toInt(), parts[0].toInt()).let { if (it.isBefore(today)) it.plusYears(1) else it }
                3 -> LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun parseWeekday(value: String?): DayOfWeek? = when (value?.trim()) {
        "الاثنين" -> DayOfWeek.MONDAY
        "الثلاثاء" -> DayOfWeek.TUESDAY
        "الأربعاء" -> DayOfWeek.WEDNESDAY
        "الخميس" -> DayOfWeek.THURSDAY
        "الجمعة" -> DayOfWeek.FRIDAY
        "السبت" -> DayOfWeek.SATURDAY
        "الأحد" -> DayOfWeek.SUNDAY
        else -> null
    }
}
