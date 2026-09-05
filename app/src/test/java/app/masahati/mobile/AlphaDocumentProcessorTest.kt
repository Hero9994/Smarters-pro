package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaDocumentProcessorTest {
    @Test
    fun genericScannerNamesMayBeAutoRenamed() {
        assertTrue(AlphaDocumentProcessor.shouldAutoRename("Scan-20260906-123000.pdf"))
        assertTrue(AlphaDocumentProcessor.shouldAutoRename("IMG_20260906.jpg"))
        assertTrue(AlphaDocumentProcessor.shouldAutoRename("document.pdf"))
    }

    @Test
    fun meaningfulUserFilenameIsPreserved() {
        assertFalse(AlphaDocumentProcessor.shouldAutoRename("Mietvertrag-Familie-Farra.pdf"))
        assertFalse(AlphaDocumentProcessor.shouldAutoRename("AOK_Bescheid.pdf"))
    }

    @Test
    fun chunkingUsesOverlapAndKeepsAllContentReachable() {
        val text = (1..220).joinToString(" ") { "wort$it" }
        val chunks = AlphaDocumentProcessor.chunkText(text, maxChars = 220, overlap = 40)
        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.length <= 220 })
        assertTrue(chunks.first().contains("wort1"))
        assertTrue(chunks.last().contains("wort220"))
    }

    @Test
    fun shortTextCreatesSingleChunk() {
        assertEquals(listOf("kurzer Text"), AlphaDocumentProcessor.chunkText("kurzer Text"))
    }
}
