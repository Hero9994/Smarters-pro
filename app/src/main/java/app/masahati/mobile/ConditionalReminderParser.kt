package app.masahati.mobile

import java.time.ZonedDateTime
import java.util.Locale

data class ConditionalReminderResolution(
    val ready: Boolean,
    val task: String,
    val dueAt: Long? = null,
    val description: String? = null,
    val clarification: String? = null
)

object ConditionalReminderParser {
    fun parse(text: String, now: ZonedDateTime): ConditionalReminderResolution? {
        val normalized = normalizeDigits(text).trim()
        val lower = normalized.lowercase(Locale.ROOT)
        val condition = findCondition(lower) ?: return null
        if (!looksLikeReminder(lower)) return null

        val duration = parseDuration(lower)
            ?: return ConditionalReminderResolution(
                ready = false,
                task = extractTask(normalized, condition).ifBlank { "هذا الأمر" },
                clarification = "بعد كم وقت تريد أن أذكّرك إذا بقي الأمر غير منجز؟"
            )

        val task = extractTask(normalized, condition).ifBlank { "هذا الأمر" }
        val due = now.plusMinutes(duration.first).toInstant().toEpochMilli()
        return ConditionalReminderResolution(
            ready = true,
            task = task.take(180),
            dueAt = due,
            description = duration.second
        )
    }

    private data class ConditionMatch(val start: Int, val end: Int)

    private fun findCondition(lower: String): ConditionMatch? {
        val tokens = listOf("إذا ما", "اذا ما", "إذا لم", "اذا لم", "if i don't", "if i do not", "wenn ich nicht")
        for (token in tokens) {
            val index = lower.indexOf(token)
            if (index >= 0) return ConditionMatch(index, index + token.length)
        }
        return null
    }

    private fun looksLikeReminder(lower: String): Boolean =
        listOf("ذكرني", "ذكّرني", "تذكير", "remind", "erinner").any(lower::contains)

    private fun parseDuration(lower: String): Pair<Long, String>? {
        if (Regex("(?:خلال|بعد)\\s+(?:يومين)").containsMatchIn(lower)) return 2L * 24L * 60L to "يومين"
        if (Regex("(?:خلال|بعد)\\s+(?:أسبوع|اسبوع)").containsMatchIn(lower)) return 7L * 24L * 60L to "أسبوع"
        if (Regex("(?:خلال|بعد)\\s+(?:ساعتين)").containsMatchIn(lower)) return 120L to "ساعتين"
        if (Regex("(?:خلال|بعد)\\s+(?:ساعة)(?:\\s|$)").containsMatchIn(lower)) return 60L to "ساعة"
        if (Regex("(?:خلال|بعد)\\s+(?:يوم)(?:\\s|$)").containsMatchIn(lower)) return 24L * 60L to "يوم"

        val arabic = Regex("(?:خلال|بعد)\\s+(\\d{1,4})\\s*(دقيقة|دقائق|دقايق|ساعة|ساعات|يوم|أيام|ايام|أسبوع|اسبوع|أسابيع|اسابيع)")
            .find(lower)
        if (arabic != null) {
            val n = arabic.groupValues[1].toLongOrNull() ?: return null
            val unit = arabic.groupValues[2]
            val minutes = when {
                unit.startsWith("د") -> n
                unit.startsWith("سا") -> n * 60L
                unit.contains("يوم") || unit.contains("أيام") || unit.contains("ايام") -> n * 24L * 60L
                else -> n * 7L * 24L * 60L
            }
            return minutes.coerceIn(1L, 525_600L) to "$n $unit"
        }

        val english = Regex("(?:within|after)\\s+(\\d{1,4})\\s*(minutes?|hours?|days?|weeks?)").find(lower)
        if (english != null) {
            val n = english.groupValues[1].toLongOrNull() ?: return null
            val unit = english.groupValues[2]
            val minutes = when {
                unit.startsWith("minute") -> n
                unit.startsWith("hour") -> n * 60L
                unit.startsWith("day") -> n * 24L * 60L
                else -> n * 7L * 24L * 60L
            }
            return minutes.coerceIn(1L, 525_600L) to "$n $unit"
        }
        return null
    }

    private fun extractTask(original: String, condition: ConditionMatch): String {
        val afterCondition = original.substring(condition.end).trim()
        val durationIndex = listOf("خلال", "بعد", "within", "after")
            .map { token -> afterCondition.indexOf(token, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull()
        var task = if (durationIndex == null) afterCondition else afterCondition.substring(0, durationIndex)
        task = task
            .replace(Regex("(?:ذكرني|ذكّرني|تذكير|remind me|erinnere mich)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '،', ',', '.', '؟', '?')
        return task
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
}
