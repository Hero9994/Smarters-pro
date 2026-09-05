package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlphaDateTrackerTest {
    @Test
    fun labelExplainsRelativeExpiry() {
        val row = TrackedDocumentRow(
            messageId = 1,
            spaceId = 2,
            displayName = "vertrag.pdf",
            smartTitle = "عقد الإيجار",
            dueDate = null,
            expiryDate = "2026-09-16",
            actionText = null
        )
        val notice = TrackedDateNotice(row, "expiry", LocalDate.of(2026,9,16), 10)
        assertTrue(AlphaDateTracker.label(notice).contains("بعد 10 يوم"))
    }

    @Test
    fun overdueLabelIsExplicit() {
        val row = TrackedDocumentRow(1,2,"x.pdf","فاتورة",null,"2026-09-01",null)
        val notice = TrackedDateNotice(row,"expiry",LocalDate.of(2026,9,1),-5)
        assertTrue(AlphaDateTracker.label(notice).contains("متأخر 5 يوم"))
    }
}
