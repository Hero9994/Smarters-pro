package app.masahati.mobile

import java.time.ZonedDateTime
import java.util.Locale

data class ContextualReminderResolution(
    val sourceText: String,
    val resolution: NaturalReminderResolution
)

object ReminderContextResolver {
    private val referenceRegex = Regex(
        "(?:فيها|فيه|بها|به|نفس(?:\\s+الموعد|\\s+الوقت)?|هاد|هالشي|هاي|هذه|هذا|يلي\\s+قبل)",
        RegexOption.IGNORE_CASE
    )

    private val scheduleHintRegex = Regex(
        "(?:[01]?\\d|2[0-3])[:.]\\d{2}|(?:الساعة|الساعه|um|at)\\s*\\d{1,2}|الاثنين|الإثنين|اثنين|اتنين|الثلاثاء|ثلاثاء|الأربعاء|الاربعاء|أربعاء|اربعاء|الخميس|خميس|الجمعة|جمعة|السبت|سبت|الأحد|الاحد|أحد|احد|monday|tuesday|wednesday|thursday|friday|saturday|sunday|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag",
        RegexOption.IGNORE_CASE
    )

    fun resolve(
        currentText: String,
        recentUserTexts: List<String>,
        now: ZonedDateTime
    ): ContextualReminderResolution? {
        if (!NaturalReminderParser.looksLikeReminder(currentText)) return null

        val direct = NaturalReminderParser.parse(currentText, now) ?: return null
        if (direct.ready) return ContextualReminderResolution(currentText, direct)

        if (!referenceRegex.containsMatchIn(currentText.lowercase(Locale.ROOT))) {
            return ContextualReminderResolution(currentText, direct)
        }

        val previous = recentUserTexts.asReversed()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && scheduleHintRegex.containsMatchIn(it) }
            ?: return ContextualReminderResolution(currentText, direct)

        val combined = previous + " " + currentText
        val contextual = NaturalReminderParser.parse(combined, now)
            ?: return ContextualReminderResolution(currentText, direct)

        return if (contextual.ready) {
            ContextualReminderResolution(combined, contextual)
        } else {
            ContextualReminderResolution(currentText, direct)
        }
    }
}
