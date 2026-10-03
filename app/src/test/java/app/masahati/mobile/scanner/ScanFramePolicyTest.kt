package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanFramePolicyTest {
    private val quality=CaptureQuality(85.0,.05,0.0,0.0,.2,true)
    @Test fun onlyFastFreshStableZslCapturesCanTakeASecondJpeg() {
        assertTrue(ScanFramePolicy.shouldAttempt(true,true,quality,100,200))
        assertFalse(ScanFramePolicy.shouldAttempt(false,true,quality,100,200))
        assertFalse(ScanFramePolicy.shouldAttempt(true,false,quality,100,200))
        assertFalse(ScanFramePolicy.shouldAttempt(true,true,quality,351,200))
        assertFalse(ScanFramePolicy.shouldAttempt(true,true,quality,100,451))
        assertFalse(ScanFramePolicy.shouldAttempt(true,true,quality.copy(focused=false),100,200))
    }
    @Test fun GoodSharpnessOrBadExposureKeepsTheSingleFramePath() {
        assertFalse(ScanFramePolicy.shouldAttempt(true,true,quality.copy(sharpness=200.0),100,200))
        assertFalse(ScanFramePolicy.shouldAttempt(true,true,quality.copy(clippedFraction=.08),100,200))
    }
    @Test fun aSharperFrameMustAlsoPreserveExposure() {
        assertTrue(ScanFramePolicy.improves(quality,quality.copy(sharpness=110.0)))
        assertFalse(ScanFramePolicy.improves(quality,quality.copy(sharpness=86.0)))
        assertFalse(ScanFramePolicy.improves(quality,quality.copy(sharpness=150.0,darkFraction=.3)))
        assertFalse(ScanFramePolicy.improves(quality,quality.copy(sharpness=150.0,glareFraction=.03)))
    }
}
