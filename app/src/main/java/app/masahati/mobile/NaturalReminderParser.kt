package app.masahati.mobile

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NaturalReminderResolution(
    val ready: Boolean,
    val clarification: String? = null,
    val delayMinutes: Int? = null,
    val triggerAt: Long? = null,
    val repeatRule: String = "none",
    val dayOfWeek: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val description: String? = null
)

object NaturalReminderParser {
    fun looksLikeReminder(text: String): Boolean {
        val lower = normalizeDigits(text).lowercase(Locale.ROOT)
        return listOf("ذكرني", "ذكّرني", "تذكير", "remind", "erinner", "erinnere mich").any(lower::contains)
    }

    fun parse(text: String, now: ZonedDateTime): NaturalReminderResolution? {
        if (!looksLikeReminder(text)) return null
        val normalized = normalizeDigits(text).lowercase(Locale.ROOT).replace("غداً", "غدا")

        readDelayMinutes(normalized)?.let { minutes ->
            val trigger = now.plusMinutes(minutes.toLong()).withNano(0).toInstant().toEpochMilli()
            return NaturalReminderResolution(
                ready = true,
                delayMinutes = minutes,
                triggerAt = trigger,
                description = "بعد " + minutes + " دقيقة"
            )
        }

        val clock = parseNaturalClock(normalized)
        val tomorrow = listOf("بكرا", "غدا", "tomorrow", "morgen").any(normalized::contains)
        val today = listOf("اليوم", "today", "heute").any(normalized::contains)
        val weekday = findWeekday(normalized)
        val explicitDate = parseDate(normalized, now.toLocalDate())
        val daily = listOf("كل يوم", "يوميا", "يومياً", "daily", "every day", "jeden tag", "täglich", "taeglich").any(normalized::contains)
        val weekly = listOf("كل اسبوع", "كل أسبوع", "اسبوعيا", "أسبوعيا", "weekly", "every week", "wöchentlich", "woechentlich").any(normalized::contains) ||
            (weekday != null && listOf("كل ", "every ", "jeden ", "jede ").any(normalized::contains))

        if (clock == null) {
            return NaturalReminderResolution(false, "أي ساعة تريد التذكير؟")
        }

        if (clock.ambiguous && !today) {
            return NaturalReminderResolution(false, "الساعة " + clock.rawHour + " صباحاً أم مساءً؟")
        }

        var hour = clock.hour
        val minute = clock.minute

        if (clock.ambiguous && today) {
            val morning = now.withHour(clock.rawHour % 12).withMinute(minute).withSecond(0).withNano(0)
            val eveningHour = if (clock.rawHour == 12) 12 else clock.rawHour + 12
            val evening = now.withHour(eveningHour).withMinute(minute).withSecond(0).withNano(0)
            val futureChoices = listOf(morning, evening).filter { it.isAfter(now) }
            if (futureChoices.isEmpty()) {
                return NaturalReminderResolution(false, "هذا الوقت مرّ اليوم. هل تقصد غداً؟")
            }
            hour = futureChoices.minBy { it.toInstant().toEpochMilli() }.hour
        }

        if (daily) {
            return NaturalReminderResolution(
                ready = true,
                repeatRule = "daily",
                hour = hour,
                minute = minute,
                triggerAt = ReminderTime.nextDaily(now, hour, minute),
                description = "كل يوم الساعة " + fmt(hour, minute)
            )
        }

        if (weekly) {
            if (weekday == null) return NaturalReminderResolution(false, "أي يوم من الأسبوع تريد التذكير؟")
            return NaturalReminderResolution(
                ready = true,
                repeatRule = "weekly",
                dayOfWeek = weekday,
                hour = hour,
                minute = minute,
                triggerAt = ReminderTime.nextWeekly(now, weekday, hour, minute),
                description = "كل " + weekdayArabic(weekday) + " الساعة " + fmt(hour, minute)
            )
        }

        explicitDate?.let { date ->
            var candidate = date.atTime(hour, minute).atZone(now.zone)
            if (!candidate.isAfter(now)) {
                if (date.year == now.year && !today) candidate = candidate.plusYears(1)
                else return NaturalReminderResolution(false, "هذا الموعد أصبح في الماضي. ما الموعد الجديد؟")
            }
            return NaturalReminderResolution(
                ready = true,
                triggerAt = candidate.toInstant().toEpochMilli(),
                hour = hour,
                minute = minute,
                description = format(candidate)
            )
        }

        if (tomorrow) {
            val candidate = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
            return NaturalReminderResolution(
                ready = true,
                triggerAt = candidate.toInstant().toEpochMilli(),
                hour = hour,
                minute = minute,
                description = format(candidate)
            )
        }

        if (today) {
            val candidate = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
            if (!candidate.isAfter(now)) return NaturalReminderResolution(false, "هذا الوقت مرّ اليوم. هل تقصد غداً؟")
            return NaturalReminderResolution(
                ready = true,
                triggerAt = candidate.toInstant().toEpochMilli(),
                hour = hour,
                minute = minute,
                description = format(candidate)
            )
        }

        if (weekday != null) {
            val trigger = ReminderTime.nextWeekly(now, weekday, hour, minute)
            return NaturalReminderResolution(
                ready = true,
                triggerAt = trigger,
                hour = hour,
                minute = minute,
                description = formatEpoch(trigger, now.zone)
            )
        }

        if (clock.ambiguous) {
            return NaturalReminderResolution(false, "الساعة " + clock.rawHour + " صباحاً أم مساءً؟")
        }

        val trigger = ReminderTime.nextDaily(now, hour, minute)
        return NaturalReminderResolution(
            ready = true,
            triggerAt = trigger,
            hour = hour,
            minute = minute,
            description = formatEpoch(trigger, now.zone)
        )
    }

    fun parseWithContext(text: String, recentUserTexts: List<String>, now: ZonedDateTime): NaturalReminderResolution? {
        val direct = parse(text, now) ?: return null
        if (direct.ready) return direct
        val normalized = normalizeDigits(text).lowercase(Locale.ROOT)
        val offset = readBeforeOffsetMinutes(normalized) ?: return direct
        val hasReference = listOf(
            "قبلها", "قبله", "قبل الموعد", "قبل التدريب", "قبلها ب", "before it", "before the", "before",
            "vorher", "davor", "vor dem", "vor der"
        ).any(normalized::contains)
        if (!hasReference) return direct

        val anchor = recentUserTexts.asReversed()
            .asSequence()
            .mapNotNull { resolveAnchorTime(it, now) }
            .firstOrNull() ?: return NaturalReminderResolution(false, "أي موعد تقصد؟ اكتب اليوم والساعة أو اذكر الموعد مرة ثانية.")

        val trigger = anchor.minusMinutes(offset.toLong())
        if (!trigger.isAfter(now)) {
            return NaturalReminderResolution(false, "وقت التذكير قبل الموعد أصبح في الماضي. متى تريد أن أذكرك؟")
        }
        return NaturalReminderResolution(
            ready = true,
            triggerAt = trigger.toInstant().toEpochMilli(),
            description = "قبل الموعد بـ" + humanOffset(offset) + " — " + format(trigger)
        )
    }

    private fun resolveAnchorTime(text: String, now: ZonedDateTime): ZonedDateTime? {
        val normalized = normalizeDigits(text).lowercase(Locale.ROOT).replace("غداً", "غدا")
        val clock = parseNaturalClock(normalized) ?: return null
        if (clock.ambiguous) return null
        val hour = clock.hour
        val minute = clock.minute
        val explicitDate = parseDate(normalized, now.toLocalDate())
        if (explicitDate != null) {
            var candidate = explicitDate.atTime(hour, minute).atZone(now.zone)
            if (!candidate.isAfter(now) && !listOf("اليوم", "today", "heute").any(normalized::contains)) {
                candidate = candidate.plusYears(1)
            }
            return candidate.takeIf { it.isAfter(now) }
        }
        if (listOf("بكرا", "غدا", "tomorrow", "morgen").any(normalized::contains)) {
            return now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
        }
        if (listOf("اليوم", "today", "heute").any(normalized::contains)) {
            return now.toLocalDate().atTime(hour, minute).atZone(now.zone).takeIf { it.isAfter(now) }
        }
        val weekday = findWeekday(normalized) ?: return null
        val epoch = ReminderTime.nextWeekly(now, weekday, hour, minute)
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), now.zone)
    }

    private fun readBeforeOffsetMinutes(text: String): Int? {
        if (Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?|before|vorher|davor).*?(?:نص|نصف)\\s+ساعة", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 30
        if (Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?|before|vorher|davor).*?ربع\\s+ساعة", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 15
        if (Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?).*?ساعتين", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 120
        if (Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?).*?(?:ب)?ساعة(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 60
        Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?).*?(\\d{1,4})\\s*(?:دقيقة|دقائق|دقايق|دقيقه)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it.coerceIn(1, 60 * 24 * 30) }
        Regex("(?:قبل(?:ها|ه| الموعد| التدريب)?).*?(\\d{1,3})\\s*(?:ساعة|ساعات|ساعه)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return (it * 60).coerceIn(1, 60 * 24 * 30) }
        Regex("(\\d{1,4})\\s*(?:minutes?|minuten?)\\s*(?:before|vorher|davor)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it.coerceIn(1, 60 * 24 * 30) }
        Regex("(\\d{1,3})\\s*(?:hours?|stunden?)\\s*(?:before|vorher|davor)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return (it * 60).coerceIn(1, 60 * 24 * 30) }
        if (Regex("(?:an?\\s+hour\\s+before|eine\\s+stunde\\s+(?:vorher|davor))", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 60
        return null
    }

    private fun humanOffset(minutes: Int): String = when (minutes) {
        15 -> "ربع ساعة"
        30 -> "نصف ساعة"
        60 -> "ساعة"
        120 -> "ساعتين"
        else -> if (minutes % 60 == 0) (minutes / 60).toString() + " ساعات" else minutes.toString() + " دقيقة"
    }

    private data class NaturalClock(val hour: Int, val minute: Int, val ambiguous: Boolean, val rawHour: Int)

    private fun parseNaturalClock(text: String): NaturalClock? {
        val colon = Regex("(?:^|\\D)([01]?\\d|2[0-3]):([0-5]\\d)(?:$|\\D)").find(text)
        if (colon != null) {
            val h = colon.groupValues[1].toInt()
            return NaturalClock(h, colon.groupValues[2].toInt(), false, h)
        }

        val match = Regex("(?:الساعة|الساعه|um|at)\\s*(\\d{1,2})(?:[:.]([0-5]\\d))?(?:\\s*uhr)?", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("(\\d{1,2})(?::([0-5]\\d))?\\s*uhr", RegexOption.IGNORE_CASE).find(text)
            ?: return null

        val raw = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        if (raw !in 0..23) return null
        if (raw > 12) return NaturalClock(raw, minute, false, raw)

        val pm = listOf("مساء", "المساء", "مسا", "بالليل", "ليل", "pm", "abends", "nachmittags").any(text::contains)
        val am = listOf("صباح", "الصبح", "صباحا", "صباحاً", "am", "morgens", "vormittags").any(text::contains)
        val hour = when {
            pm && raw < 12 -> raw + 12
            am && raw == 12 -> 0
            else -> raw
        }
        return NaturalClock(hour, minute, !pm && !am && raw in 1..12 && match.groupValues.getOrNull(2).isNullOrBlank(), raw)
    }

    private fun readDelayMinutes(text: String): Int? {
        if (Regex("بعد\\s+(?:نص|نصف)\\s+ساعة").containsMatchIn(text)) return 30
        if (Regex("بعد\\s+ربع\\s+ساعة").containsMatchIn(text)) return 15
        if (Regex("بعد\\s+ساعتين").containsMatchIn(text)) return 120
        if (Regex("بعد\\s+ساعة(?:\\s|$)").containsMatchIn(text)) return 60

        Regex("بعد\\s+(\\d{1,5})\\s*(?:دقيقة|دقائق|دقايق|دقيقه)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it.coerceIn(1, 60 * 24 * 30)
        }
        Regex("بعد\\s+(\\d{1,4})\\s*(?:ساعة|ساعات|ساعه)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return (it * 60).coerceIn(1, 60 * 24 * 30)
        }
        Regex("(?:in|after)\\s+(\\d{1,5})\\s*(?:minutes?|minuten?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return it.coerceIn(1, 60 * 24 * 30)
        }
        Regex("(?:in|after)\\s+(\\d{1,4})\\s*(?:hours?|stunden?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return (it * 60).coerceIn(1, 60 * 24 * 30)
        }
        return null
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        val m = Regex("(?:^|\\D)(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{2,4}))?(?:$|\\D)").find(text) ?: return null
        val day = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val yearRaw = m.groupValues[3].toIntOrNull()
        val year = when {
            yearRaw == null -> today.year
            yearRaw < 100 -> 2000 + yearRaw
            else -> yearRaw
        }
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private fun findWeekday(text: String): Int? {
        val tokens = listOf(
            "الاثنين", "الإثنين", "اثنين", "الثلاثاء", "ثلاثاء", "الأربعاء", "الاربعاء", "أربعاء", "اربعاء",
            "الخميس", "خميس", "الجمعة", "جمعة", "السبت", "سبت", "الأحد", "الاحد", "أحد", "احد",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag"
        )
        return tokens.firstNotNullOfOrNull { token ->
            if (text.contains(token, ignoreCase = true)) ReminderTime.parseWeekday(token) else null
        }
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '٠', '۰' -> '0'
                    '١', '۱' -> '1'
                    '٢', '۲' -> '2'
                    '٣', '۳' -> '3'
                    '٤', '۴' -> '4'
                    '٥', '۵' -> '5'
                    '٦', '۶' -> '6'
                    '٧', '۷' -> '7'
                    '٨', '۸' -> '8'
                    '٩', '۹' -> '9'
                    else -> ch
                }
            )
        }
    }

    private fun fmt(hour: Int, minute: Int): String =
        hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')

    private fun weekdayArabic(day: Int): String = when (day) {
        1 -> "الاثنين"
        2 -> "الثلاثاء"
        3 -> "الأربعاء"
        4 -> "الخميس"
        5 -> "الجمعة"
        6 -> "السبت"
        else -> "الأحد"
    }

    private fun format(value: ZonedDateTime): String =
        value.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY))

    private fun formatEpoch(epoch: Long, zone: ZoneId): String =
        ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epoch), zone)
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY))
}
