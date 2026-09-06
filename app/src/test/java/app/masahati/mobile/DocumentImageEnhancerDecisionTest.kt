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

    @Test
    fun whiteBalanceCorrectsMildPaperColorCast() {
        val gains = DocumentImageEnhancer.paperWhiteBalanceGains(
            averageRed = 232f,
            averageGreen = 218f,
            averageBlue = 199f,
            brightFraction = 0.72f
        )
        assertTrue(gains != null)
        assertTrue(gains!!.third > gains.first)
    }

    @Test
    fun whiteBalanceDoesNotTouchAlreadyNeutralPaper() {
        val gains = DocumentImageEnhancer.paperWhiteBalanceGains(
            averageRed = 230f,
            averageGreen = 229f,
            averageBlue = 228f,
            brightFraction = 0.74f
        )
        assertTrue(gains == null)
    }

    @Test
    fun whiteBalanceDoesNotTouchMostlyNonPaperImage() {
        val gains = DocumentImageEnhancer.paperWhiteBalanceGains(
            averageRed = 220f,
            averageGreen = 205f,
            averageBlue = 190f,
            brightFraction = 0.18f
        )
        assertTrue(gains == null)
    }

}
