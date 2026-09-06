package app.masahati.mobile

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TodayTaskStatus {
    OPEN,
    SKIPPED,
    DONE
}

enum class TodayTaskKind(val sourceType: String) {
    ACTION("action"),
    REMINDER("reminder"),
    DOCUMENT_DUE("document_due"),
    DOCUMENT_EXPIRY("document_expiry")
}

data class TodayTaskItem(
    val kind: TodayTaskKind,
    val sourceId: Long,
    val title: String,
    val detail: String?,
    val scheduledAt: Long?,
    val status: TodayTaskStatus,
    val statusUpdatedAt: Long?,
    val originSpaceId: Long?,
    val originSpaceTitle: String?,
    val overdue: Boolean = false
) {
    val key: String get() = "${kind.sourceType}:$sourceId"
}

data class TodayTaskSummary(
    val open: Int,
    val skipped: Int,
    val done: Int
) {
    val total: Int get() = open + skipped + done
}

data class TodayHomeSnapshot(
    val total: Int,
    val open: Int,
    val overdue: Int,
    val skipped: Int,
    val done: Int,
    val nextTitle: String?
)

object TodayTasksEngine {
    fun build(
        db: MasahatiDatabase,
        now: ZonedDateTime = ZonedDateTime.now()
    ): List<TodayTaskItem> {
        val zone = now.zone
        val date = now.toLocalDate()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dateKey = date.toString()
        val states = db.listTodayTaskStates(dateKey)
            .associateBy { "${it.sourceType}:${it.sourceId}" }

        val actions = db.listTodayActionItems(start, end)
        val actionIds = actions.mapTo(mutableSetOf()) { it.id }
        val actionMessageIds = actions.mapNotNullTo(mutableSetOf()) { it.messageId }

        val items = mutableListOf<TodayTaskItem>()

        actions.forEach { action ->
            val status = when (action.status) {
                "done" -> TodayTaskStatus.DONE
                "skipped" -> TodayTaskStatus.SKIPPED
                else -> TodayTaskStatus.OPEN
            }
            val spaceTitle = db.getSpace(action.spaceId)?.title
            val due = action.dueAt
            items += TodayTaskItem(
                kind = TodayTaskKind.ACTION,
                sourceId = action.id,
                title = action.title.ifBlank { "مهمة" },
                detail = action.details?.takeIf { it.isNotBlank() },
                scheduledAt = due,
                status = status,
                statusUpdatedAt = if (status == TodayTaskStatus.OPEN) null else action.updatedAt,
                originSpaceId = action.spaceId,
                originSpaceTitle = spaceTitle,
                overdue = status == TodayTaskStatus.OPEN && due != null && due < start
            )
        }

        db.listTodayReminders(start, end).forEach { reminder ->
            if (reminder.conditionActionId != null && reminder.conditionActionId in actionIds) {
                return@forEach
            }
            val state = states["${TodayTaskKind.REMINDER.sourceType}:${reminder.id}"]
            val scheduled = reminder.nextFireAt
                ?.takeIf { it in start until end }
                ?: reminder.deliveredAt?.takeIf { it in start until end }
            if (scheduled == null) return@forEach

            val task = ReminderDeliveryText.cleanTask(reminder.body)
                .ifBlank { reminder.title.ifBlank { "تذكير" } }
            items += TodayTaskItem(
                kind = TodayTaskKind.REMINDER,
                sourceId = reminder.id,
                title = task,
                detail = reminder.title.takeIf { it.isNotBlank() && it != task },
                scheduledAt = scheduled,
                status = state?.status.toTodayStatus(),
                statusUpdatedAt = state?.updatedAt,
                originSpaceId = reminder.spaceId,
                originSpaceTitle = db.getSpace(reminder.spaceId)?.title,
                overdue = false
            )
        }

        AlphaDateTracker.upcoming(
            db = db,
            today = date,
            horizonDays = 0,
            includeOverdueDays = 0
        ).forEach { notice ->
            if (notice.kind == "due" && notice.source.messageId in actionMessageIds) {
                return@forEach
            }
            val kind = if (notice.kind == "expiry") TodayTaskKind.DOCUMENT_EXPIRY else TodayTaskKind.DOCUMENT_DUE
            val state = states["${kind.sourceType}:${notice.source.messageId}"]
            val title = notice.source.smartTitle
                ?: notice.source.displayName
                ?: if (kind == TodayTaskKind.DOCUMENT_EXPIRY) "انتهاء مستند" else "موعد مستند"
            val detail = when (kind) {
                TodayTaskKind.DOCUMENT_EXPIRY -> "تنتهي صلاحيته اليوم"
                else -> notice.source.actionText?.takeIf { it.isNotBlank() } ?: "موعد متعلق بهذا المستند اليوم"
            }
            items += TodayTaskItem(
                kind = kind,
                sourceId = notice.source.messageId,
                title = title,
                detail = detail,
                scheduledAt = null,
                status = state?.status.toTodayStatus(),
                statusUpdatedAt = state?.updatedAt,
                originSpaceId = notice.source.spaceId,
                originSpaceTitle = db.getSpace(notice.source.spaceId)?.title,
                overdue = false
            )
        }

        return sort(items)
    }

    fun summary(items: List<TodayTaskItem>): TodayTaskSummary =
        TodayTaskSummary(
            open = items.count { it.status == TodayTaskStatus.OPEN },
            skipped = items.count { it.status == TodayTaskStatus.SKIPPED },
            done = items.count { it.status == TodayTaskStatus.DONE }
        )

    fun homeSnapshot(items: List<TodayTaskItem>): TodayHomeSnapshot {
        val sorted = sort(items)
        val summary = summary(sorted)
        return TodayHomeSnapshot(
            total = summary.total,
            open = summary.open,
            overdue = sorted.count { it.status == TodayTaskStatus.OPEN && it.overdue },
            skipped = summary.skipped,
            done = summary.done,
            nextTitle = sorted.firstOrNull { it.status == TodayTaskStatus.OPEN }?.title
        )
    }

    fun sort(items: List<TodayTaskItem>): List<TodayTaskItem> =
        items.sortedWith(
            compareBy<TodayTaskItem> { statusRank(it.status) }
                .thenComparator { a, b ->
                    if (a.status == TodayTaskStatus.OPEN && b.status == TodayTaskStatus.OPEN) {
                        when {
                            a.overdue && !b.overdue -> -1
                            !a.overdue && b.overdue -> 1
                            else -> compareScheduled(a.scheduledAt, b.scheduledAt)
                        }
                    } else {
                        val au = a.statusUpdatedAt ?: Long.MIN_VALUE
                        val bu = b.statusUpdatedAt ?: Long.MIN_VALUE
                        when {
                            au > bu -> -1
                            au < bu -> 1
                            else -> compareScheduled(a.scheduledAt, b.scheduledAt)
                        }
                    }
                }
                .thenBy { it.title.lowercase(Locale.ROOT) }
        )

    fun dateLabel(date: LocalDate): String =
        "${arabicDayName(date.dayOfWeek)} ${date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY))}"

    fun dateKey(now: ZonedDateTime = ZonedDateTime.now()): String = now.toLocalDate().toString()

    fun startOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun statusRank(status: TodayTaskStatus): Int = when (status) {
        TodayTaskStatus.OPEN -> 0
        TodayTaskStatus.SKIPPED -> 1
        TodayTaskStatus.DONE -> 2
    }

    private fun compareScheduled(a: Long?, b: Long?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> a.compareTo(b)
    }

    private fun String?.toTodayStatus(): TodayTaskStatus = when (this) {
        "done" -> TodayTaskStatus.DONE
        "skipped" -> TodayTaskStatus.SKIPPED
        else -> TodayTaskStatus.OPEN
    }

    private fun arabicDayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "الاثنين"
        DayOfWeek.TUESDAY -> "الثلاثاء"
        DayOfWeek.WEDNESDAY -> "الأربعاء"
        DayOfWeek.THURSDAY -> "الخميس"
        DayOfWeek.FRIDAY -> "الجمعة"
        DayOfWeek.SATURDAY -> "السبت"
        DayOfWeek.SUNDAY -> "الأحد"
    }
}
