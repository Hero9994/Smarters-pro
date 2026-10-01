package app.masahati.mobile.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanGeometryTest {
    @Test fun rejectsCrossedOrInvalidCorners() {
        assertFalse(ScanGeometry.valid(listOf(ScanPoint(0.0,0.0),ScanPoint(1.0,1.0),ScanPoint(1.0,0.0),ScanPoint(0.0,1.0))))
        assertFalse(ScanGeometry.valid(DocumentQuad.inset().points.toMutableList().apply { set(0,ScanPoint(Double.NaN,0.0)) }))
    }
    @Test fun receiptRatioIsNotForcedToA4() {
        val p=listOf(ScanPoint(.2,.01),ScanPoint(.8,.01),ScanPoint(.8,.99),ScanPoint(.2,.99))
        val (w,h)=ScanGeometry.outputSize(p,600,3300,3200)
        assertTrue(h.toDouble()/w>8);assertEquals(3200,h)
    }
    @Test fun robustFitRejectsDistractingBackgroundLines() {
        val samples=(0..39).map { EdgeSample(ScanPoint(it*10.0,20.0+(it%3-1)*.2)) }+
            (0..17).map { EdgeSample(ScanPoint(it*17.0,it*14.0+60)) }
        val line=ScanGeometry.robustLine(samples)!!
        assertTrue(line.distance(ScanPoint(195.0,20.0))<.3);assertTrue(line.inlierFraction>.65)
    }
    @Test fun paddingIsOutsideEachOriginalEdge() {
        val p=listOf(ScanPoint(.1,.13),ScanPoint(.86,.09),ScanPoint(.91,.9),ScanPoint(.12,.93))
        val padded=ScanGeometry.padded(p,3000,4000,4.0)
        assertTrue(ScanGeometry.area(padded)>ScanGeometry.area(p))
        for(i in 0..3) {
            val line=ScanGeometry.lineThrough(ScanPoint(p[i].x*2999,p[i].y*3999),ScanPoint(p[(i+1)%4].x*2999,p[(i+1)%4].y*3999))!!
            assertEquals(4.0,line.distance(ScanPoint(padded[i].x*2999,padded[i].y*3999)),1e-6)
        }
    }
    @Test fun intersectionKeepsSubpixels() {
        val p=ScanGeometry.intersection(FittedLine(1.0,0.0,-10.25),FittedLine(0.0,1.0,-30.75))!!
        assertEquals(10.25,p.x,1e-10);assertEquals(30.75,p.y,1e-10)
    }
}
