package app.masahati.mobile.scanner

/** Source text near or outside a proposed crop must be checked BEFORE warping.
 * Checking only the already-cropped image cannot discover a lost reference row.
 * The coarse document polygon excludes unrelated writing in the background.
 */
object ScanCropContentGuard {
    fun atRisk(boxes: List<ScanTextBox>,crop: DocumentQuad,expectedPaper: DocumentQuad): List<ScanTextBox> {
        require(crop.valid() && expectedPaper.valid())
        fun inside(quad: DocumentQuad,p: ScanPoint)=quad.points.indices.all { i ->
            val a=quad.points[i];val b=quad.points[(i+1)%4]
            (b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x)>=-1e-8
        }
        return boxes.filter { box ->
            val center=ScanPoint((box.left+box.right)/2,(box.top+box.bottom)/2)
            if(!inside(expectedPaper,center)) return@filter false
            // OCR detectors pad their rectangles. Inspect the inner area so
            // merely clipping that padding does not count as lost ink.
            val x=(box.right-box.left)*.08;val y=(box.bottom-box.top)*.16
            listOf(ScanPoint(box.left+x,box.top+y),ScanPoint(box.right-x,box.top+y),
                ScanPoint(box.right-x,box.bottom-y),ScanPoint(box.left+x,box.bottom-y)).any { !inside(crop,it) }
        }
    }
}
