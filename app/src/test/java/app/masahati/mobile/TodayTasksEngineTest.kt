package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayTasksEngineTest {
    @Test
    fun statusOrderIsOpenThenSkippedThenDone() {
        val items = listOf(
            item(3, TodayTaskStatus.DONE, 300),
            item(2, TodayTaskStatus.SKIPPED, 200),
            item(1, TodayTaskStatus.OPEN, 100)
        )
        assertEquals(
            listOf(TodayTaskStatus.OPEN, TodayTaskStatus.SKIPPED, TodayTaskStatus.DONE),
            TodayTasksEngine.sort(items).map { it.status }
        )
    }

    @Test
    fun overdueOpenTaskComesBeforeOtherOpenTasks() {
        val normal = item(1, TodayTaskStatus.OPEN, 100, overdue = false)
        val overdue = item(2, TodayTaskStatus.OPEN, 200, overdue = true)
        assertEquals(2L, TodayTasksEngine.sort(listOf(normal, overdue)).first().sourceId)
    }

    @Test
    fun recentlyChangedSkippedItemStaysAboveDoneSection() {
        val skipped = item(1, TodayTaskStatus.SKIPPED, 100, updated = 500)
        val done = item(2, TodayTaskStatus.DONE, 50, updated = 1000)
        val sorted = TodayTasksEngine.sort(listOf(done, skipped))
        assertEquals(TodayTaskStatus.SKIPPED, sorted.first().status)
        assertEquals(TodayTaskStatus.DONE, sorted.last().status)
    }

    @Test
    fun arabicTodayLabelMatchesRequestedFormat() {
        assertEquals(
            "الأحد 06.09.2026",
            TodayTasksEngine.dateLabel(LocalDate.of(2026, 9, 6))
        )
    }

    @Test
    fun summaryCountsAllThreeStates() {
        val summary = TodayTasksEngine.summary(
            listOf(
                item(1, TodayTaskStatus.OPEN, 1),
                item(2, TodayTaskStatus.OPEN, 2),
                item(3, TodayTaskStatus.SKIPPED, 3),
                item(4, TodayTaskStatus.DONE, 4)
            )
        )
        assertEquals(2, summary.open)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.done)
        assertEquals(4, summary.total)
    }

    private fun item(
        id: Long,
        status: TodayTaskStatus,
        time: Long,
        updated: Long? = null,
        overdue: Boolean = false
    ) = TodayTaskItem(
        kind = TodayTaskKind.ACTION,
        sourceId = id,
        title = "Task $id",
        detail = null,
        scheduledAt = time,
        status = status,
        statusUpdatedAt = updated,
        originSpaceId = 1,
        originSpaceTitle = "Test",
        overdue = overdue
    )
}
