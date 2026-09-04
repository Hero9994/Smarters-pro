package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIntelligenceTest {
    private fun doc(ocr: String = "Mietvertrag. Vertragsbeginn 01.10.2026. Vertragsende 30.09.2027.") =
        MessageRow(
            id = 9L,
            spaceId = 1L,
            role = "user",
            kind = "file",
            text = "",
            filePath = "/tmp/a.pdf",
            mimeType = "application/pdf",
            displayName = "Scan-20260904-195444.pdf",
            ocrText = ocr,
            classification = "document",
            tags = null,
            summary = null,
            starred = false,
            createdAt = 1L
        )

    @Test
    fun filenameQuestionNeverNeedsModel() {
        val result = DocumentIntelligence.directAnswer("شو سميتها؟", doc())
        assertNotNull(result)
        assertEquals("اسم الملف: Scan-20260904-195444.pdf", result!!.getString("reply"))
    }

    @Test
    fun blankOcrDoesNotHallucinate() {
        val result = DocumentIntelligence.directAnswer("شو فيها؟", doc(""))
        assertNotNull(result)
        assertTrue(result!!.getString("reply").contains("لن أخمّن"))
    }

    @Test
    fun readableContentQuestionGoesToReasoningModel() {
        assertNull(DocumentIntelligence.directAnswer("شو محتوى الورقة؟", doc()))
    }

    @Test
    fun endDateCanBeAnsweredFromGroundTruth() {
        val result = DocumentIntelligence.directAnswer("متى بينتهي العقد؟", doc())
        assertNotNull(result)
        assertTrue(result!!.getString("reply").contains("30.09.2027"))
    }
}
