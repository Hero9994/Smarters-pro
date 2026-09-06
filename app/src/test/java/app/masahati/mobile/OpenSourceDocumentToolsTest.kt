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
}
