package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanCropContentGuardTest {
    @Test fun headerOutsideProposedCropIsProtectedButBackgroundWritingIsExcluded() {
        val paper=DocumentQuad.inset(.1)
        val crop=DocumentQuad(listOf(ScanPoint(.1,.25),ScanPoint(.9,.25),ScanPoint(.9,.9),ScanPoint(.1,.9)))
        val header=ScanTextBox(.2,.14,.7,.20)
        val body=ScanTextBox(.2,.4,.7,.46)
        val background=ScanTextBox(.01,.01,.07,.07)
        assertEquals(listOf(header),ScanCropContentGuard.atRisk(listOf(header,body,background),crop,paper))
    }
    @Test fun diagonalEdgeCrossingInkIsProtectedEvenWhenBoxCenterIsInside() {
        val crop=DocumentQuad(listOf(ScanPoint(.2,.1),ScanPoint(.9,.1),ScanPoint(.8,.9),ScanPoint(.1,.9)))
        val crossing=ScanTextBox(.14,.2,.34,.26)
        assertEquals(listOf(crossing),ScanCropContentGuard.atRisk(listOf(crossing),crop,DocumentQuad.inset(0.0)))
    }
    @Test fun fullImageAndClippedDetectorPaddingDoNotInventLostContent() {
        val box=ScanTextBox(.08,.3,.8,.36)
        assertTrue(ScanCropContentGuard.atRisk(listOf(box),DocumentQuad.inset(0.0),DocumentQuad.inset(0.0)).isEmpty())
        val crop=DocumentQuad(listOf(ScanPoint(.09,0.0),ScanPoint(1.0,0.0),ScanPoint(1.0,1.0),ScanPoint(.09,1.0)))
        assertTrue(ScanCropContentGuard.atRisk(listOf(box),crop,DocumentQuad.inset(0.0)).isEmpty())
    }
}
