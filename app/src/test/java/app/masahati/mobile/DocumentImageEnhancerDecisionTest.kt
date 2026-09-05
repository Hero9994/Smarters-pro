package app.masahati.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentImageEnhancerDecisionTest {
    @Test
    fun correctedPageMustMeaningfullyImproveQuality() {
        assertFalse(DocumentImageEnhancer.preferCorrected(70f, 70.8f))
        assertTrue(DocumentImageEnhancer.preferCorrected(70f, 72f))
    }

    @Test
    fun worseCorrectionIsRejected() {
        assertFalse(DocumentImageEnhancer.preferCorrected(74f, 69f))
    }
}
