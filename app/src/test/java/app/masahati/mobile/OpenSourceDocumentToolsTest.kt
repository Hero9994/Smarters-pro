package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSourceDocumentToolsTest {
    @Test
    fun extractsFirstWebUrlWithoutTrailingPunctuation() {
        assertEquals(
            "https://example.com/a?b=1",
            OpenSourceDocumentTools.findFirstWebUrl("شوف https://example.com/a?b=1،")
        )
    }

    @Test
    fun returnsNullWhenNoWebUrlExists() {
        assertNull(OpenSourceDocumentTools.findFirstWebUrl("ملاحظة عادية بدون رابط"))
    }

    @Test
    fun detectsArabicGermanAndEnglish() {
        val ar = "هذا مستند رسمي يحتوي على معلومات مهمة حول العقد والموعد النهائي ويجب الاحتفاظ به للرجوع إليه لاحقاً. ".repeat(3)
        val de = "Dieser Bescheid enthält wichtige Informationen über den Vertrag, die Frist und die erforderlichen Unterlagen für den Antrag. ".repeat(3)
        val en = "This document contains important information about the contract, the deadline and the documents required for the application. ".repeat(3)
        assertEquals("ar", OpenSourceDocumentTools.detectLanguage(ar))
        assertEquals("de", OpenSourceDocumentTools.detectLanguage(de))
        assertEquals("en", OpenSourceDocumentTools.detectLanguage(en))
    }

}
