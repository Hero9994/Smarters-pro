package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanHdrPolicyTest {
    private val shadow=CaptureQuality(90.0,.1,.0,.0,.7,true)
    @Test fun goodLightDoesNotPayForMultiFrameCapture() {
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,true,shadow.copy(shadowSeverity=.12),100))
    }
    @Test fun onlyFastSupportedStableCaptureCanUseHdr() {
        assertTrue(ScanHdrPolicy.shouldAttempt(true,800,true,shadow,100))
        assertFalse(ScanHdrPolicy.shouldAttempt(false,800,true,shadow,100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,null,true,shadow,100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,3000,true,shadow,100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,false,shadow,100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,true,shadow,501))
    }
    @Test fun BlurredOrUnfocusedPaperKeepsTheNormalCapture() {
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,true,shadow.copy(focused=false),100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,true,shadow.copy(sharpness=20.0),100))
        assertFalse(ScanHdrPolicy.shouldAttempt(true,800,true,shadow.copy(darkFraction=.6),100))
    }
}
