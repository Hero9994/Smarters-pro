package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class StableCaptureGateTest {
    private val good=CaptureQuality(180.0,.03,0.0,0.0,.1)
    private val page=DocumentQuad.inset(.10).copy(confidence=.94,cornerConfidence=List(4){.97})
    @Test fun requiresBothTimeAndConsecutiveFrames() {
        val gate=StableCaptureGate();repeat(7) { assertFalse(gate.observe(page,good,it*140L)) };assertTrue(gate.observe(page,good,980))
    }
    @Test fun badFocusOrMovementImmediatelyResets() {
        val gate=StableCaptureGate();repeat(7) { gate.observe(page,good,it*160L) }
        assertFalse(gate.observe(page,good.copy(focused=false),1200));assertFalse(gate.observe(page,good,1400))
        val moved=page.copy(points=page.points.map { ScanPoint(it.x+.02,it.y) })
        assertFalse(gate.observe(moved,good,1600))
    }
    @Test fun missingCornerAndLowConfidenceNeverTrigger() {
        val gate=StableCaptureGate();repeat(15) { assertFalse(gate.observe(page.copy(confidence=.5),good,it*200L)) }
        repeat(15) { assertFalse(gate.observe(page.copy(points=page.points.toMutableList().apply { set(0,ScanPoint(0.0,.1)) }),good,it*200L)) }
    }
}
