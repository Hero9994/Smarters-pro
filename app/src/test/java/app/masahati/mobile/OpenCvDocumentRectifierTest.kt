package app.masahati.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCvDocumentRectifierTest {
    @Test
    fun acceptsPlausibleDocumentQuadrilateralOnly() {
        assertTrue(OpenCvDocumentRectifier.shouldRectify(0.72, 0.08, 0.62))
        assertFalse(OpenCvDocumentRectifier.shouldRectify(0.20, 0.08, 0.62))
        assertFalse(OpenCvDocumentRectifier.shouldRectify(0.72, 0.60, 0.62))
        assertFalse(OpenCvDocumentRectifier.shouldRectify(0.72, 0.08, 0.10))
    }

    @Test
    fun ordersCornersClockwiseFromTopLeft() {
        val ordered = OpenCvDocumentRectifier.order(
            listOf(
                ScanPoint(890.0, 1150.0),
                ScanPoint(110.0, 90.0),
                ScanPoint(820.0, 120.0),
                ScanPoint(80.0, 1080.0)
            )
        )
        assertEquals(ScanPoint(110.0, 90.0), ordered[0])
        assertEquals(ScanPoint(820.0, 120.0), ordered[1])
        assertEquals(ScanPoint(890.0, 1150.0), ordered[2])
        assertEquals(ScanPoint(80.0, 1080.0), ordered[3])
    }
}
