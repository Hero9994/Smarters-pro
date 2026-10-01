package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanOrientationTest {
    @Test fun allExifAndManualRotationsRoundTrip() {
        for(exif in 1..8) for(turns in 0..3) {
            val info=ScanImageInfo(4032,3024,exif,turns)
            for(p in listOf(ScanPoint(.1,.15),ScanPoint(.84,.9),ScanPoint(.55,.71))) {
                val result=info.raw(info.upright(p));assertEquals(p.x,result.x,1e-10);assertEquals(p.y,result.y,1e-10)
            }
        }
    }
    @Test fun portraitSensorCoordinatesAreMappedBeforeCrop() {
        val info=ScanImageInfo(4032,3024,6)
        assertEquals(3024,info.width);assertEquals(4032,info.height)
        assertEquals(ScanPoint(1.0,0.0),info.upright(ScanPoint(0.0,0.0)))
    }
}
