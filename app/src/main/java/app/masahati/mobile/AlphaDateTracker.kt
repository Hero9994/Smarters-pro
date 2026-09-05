package app.masahati.mobile

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TrackedDateNotice(
    val source: TrackedDocumentRow,
    val kind: String,
    val date: LocalDate,
    val daysUntil: Long
)

object AlphaDateTracker {
    fun upcoming(
        db: MasahatiDatabase,
        today: LocalDate = LocalDate.now(),
        horizonDays: Long = 90,
        includeOverdueDays: Long = 365
    ): List<TrackedDateNotice> {
        val notices = mutableListOf<TrackedDateNotice>()
        db.listTrackedDocuments().forEach { row ->
            parse(row.dueDate)?.let { date ->
                val days = ChronoUnit.DAYS.between(today, date)
                if (days in -includeOverdueDays..horizonDays) {
                    notices += TrackedDateNotice(row, "due", date, days)
                }
            }
            parse(row.expiryDate)?.let { date ->
                val days = ChronoUnit.DAYS.between(today, date)
                if (days in -includeOverdueDays..horizonDays) {
                    notices += TrackedDateNotice(row, "expiry", date, days)
                }
            }
        }
        return notices.sortedWith(
            compareBy<TrackedDateNotice> { it.date }
                .thenBy { it.source.smartTitle ?: it.source.displayName ?: "" }
        )
    }

    fun label(notice: TrackedDateNotice): String {
        val title = notice.source.smartTitle ?: notice.source.displayName ?: "مستند"
        val type = if (notice.kind == "due") "موعد" else "انتهاء"
        val relative = when {
            notice.daysUntil < 0 -> "متأخر ${-notice.daysUntil} يوم"
            notice.daysUntil == 0L -> "اليوم"
            notice.daysUntil == 1L -> "غداً"
            else -> "بعد ${notice.daysUntil} يوم"
        }
        return "$title — $type ${notice.date} ($relative)"
    }

    private fun parse(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
