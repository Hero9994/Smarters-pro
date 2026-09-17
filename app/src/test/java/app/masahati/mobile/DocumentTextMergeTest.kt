package app.masahati.mobile

import org.junit.Assert.*
import org.junit.Test

class DocumentTextMergeTest {
    @Test fun preservesTextLayerAndAddsArabicFromImageOnSamePage() {
        val text = DocumentTextMerge.merge("Contract 7319\nTotal: 99.50 EUR", "Contract 7319\nعقد إيجار المنزل\nTotal: 99.50 EUR")
        assertEquals("Contract 7319\nTotal: 99.50 EUR\nعقد إيجار المنزل", text)
    }

    @Test fun damagedTextLayerDoesNotReplaceRecognizedText() {
        assertEquals("عقد إيجار", DocumentTextMerge.merge("\uFFFD\uFFFD??", "عقد إيجار"))
        assertFalse(DocumentTextMerge.useful("\uFFFD\uFFFDabc"))
    }
}
