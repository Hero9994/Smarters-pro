package app.masahati.mobile

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImageAutoLevelsTest {
    @Test
    fun cleanBrightDocumentIsLeftUntouched() {
        assertNull(DocumentImageEnhancer.autoLevels(20, 245))
    }

    @Test
    fun dullGrayDocumentGetsGentleEnhancement() {
        val levels = DocumentImageEnhancer.autoLevels(55, 205)
        assertNotNull(levels)
        assertTrue(levels!!.first in 1.0f..1.16f)
        assertTrue(levels.second in -10f..22f)
    }

    @Test
    fun enhancementNeverUsesAggressiveGain() {
        val levels = DocumentImageEnhancer.autoLevels(100, 165)
        assertNotNull(levels)
        assertTrue(levels!!.first <= 1.16f)
    }
}
