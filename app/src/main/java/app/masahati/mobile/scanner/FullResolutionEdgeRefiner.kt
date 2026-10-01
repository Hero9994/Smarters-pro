package app.masahati.mobile.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import app.masahati.mobile.OpenCvDocumentRectifier
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

data class EdgeRefinement(val quad: DocumentQuad,val acceptedEdges: Int,val residualPixels: List<Double>,
    val inlierFractions: List<Double>,val elapsedMs: Long,val needsManualReview: Boolean,
    val boundaryQuad: DocumentQuad=quad,val paddingPixels: Double=0.0)

/** Native-resolution Sobel profiles in segmented source corridors. Subpixel peaks,
 * RANSAC/TLS line estimates and intersections. No global findContours/approxPolyDP crop.
 */
object FullResolutionEdgeRefiner {
    @Suppress("DEPRECATION")
    fun refine(file: File,initial: DocumentQuad,turns: Int=0): EdgeRefinement {
        require(initial.valid());check(OpenCvDocumentRectifier.isAvailable())
        val started=System.nanoTime();val info=ScanSourceImage.info(file,turns)
        val raw=initial.points.map(info::raw).map { ScanPoint(it.x*(info.rawWidth-1),it.y*(info.rawHeight-1)) }
        val radius=(min(info.rawWidth,info.rawHeight)*.027).coerceIn(20.0,180.0)
        val decoder=BitmapRegionDecoder.newInstance(file.absolutePath,false) ?: error("تعذر قراءة الحواف")
        val lines=ArrayList<FittedLine>();val residuals=ArrayList<Double>();val fractions=ArrayList<Double>();var accepted=0
        try {
            for(edge in 0..3) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                val a=raw[edge];val b=raw[(edge+1)%4];val length=a.distance(b)
                val nx=-(b.y-a.y)/length;val ny=(b.x-a.x)/length;val samples=ArrayList<ProfileEdgeSample>()
                val positions=(0 until 96).map { i -> val t=.025+i/95.0*.95
                    ScanPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t) }
                for((groupIndex,group) in positions.chunked(6).withIndex()) {
                    val extremes=group.flatMap { p -> listOf(ScanPoint(p.x-nx*(radius+8),p.y-ny*(radius+8)),
                        ScanPoint(p.x+nx*(radius+8),p.y+ny*(radius+8))) }
                    val rect=Rect(max(0,floor(extremes.minOf { it.x }).toInt()-5),max(0,floor(extremes.minOf { it.y }).toInt()-5),
                        min(info.rawWidth,ceil(extremes.maxOf { it.x }).toInt()+6),min(info.rawHeight,ceil(extremes.maxOf { it.y }).toInt()+6))
                    if(rect.width()<8 || rect.height()<8 || rect.width().toLong()*rect.height()>1_500_000) continue
                    val patch=decoder.decodeRegion(rect,BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: continue
                    val rgba=Mat();val gray=Mat();val gx=Mat();val gy=Mat()
                    try {
                        Utils.bitmapToMat(patch,rgba);patch.recycle();Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                        Imgproc.GaussianBlur(gray,gray,Size(3.0,3.0),.6)
                        Imgproc.Sobel(gray,gx,CvType.CV_32F,1,0,3);Imgproc.Sobel(gray,gy,CvType.CV_32F,0,1,3)
                        val count=rect.width()*rect.height();val xs=FloatArray(count);val ys=FloatArray(count);val lum=ByteArray(count)
                        gx.get(0,0,xs);gy.get(0,0,ys);gray.get(0,0,lum)
                        for((localIndex,p) in group.withIndex()) {
                            fun index(d: Double): Int {
                                val x=(p.x+nx*d-rect.left).roundToInt();val y=(p.y+ny*d-rect.top).roundToInt()
                                return if(x in 1 until rect.width()-1 && y in 1 until rect.height()-1) y*rect.width()+x else -1 }
                            fun value(d: Double): Double { val i=index(d);return if(i<0) 0.0 else abs(xs[i]*nx+ys[i]*ny) }
                            val range=radius.roundToInt();val candidates=ArrayList<Pair<Int,Double>>()
                            for(d in -range+6 until range-6) {
                                val strength=value(d.toDouble())
                                if(strength<24 || strength<value(d-1.0) || strength<value(d+1.0)) continue
                                val before=index(d-5.0);val after=index(d+5.0);if(before<0 || after<0) continue
                                val step=abs((lum[before].toInt() and 255)-(lum[after].toInt() and 255));if(step<4) continue
                                // Keep competing sustained transitions. Choosing the
                                // strongest one independently let printing defeat
                                // weak physical paper edges on textured backgrounds.
                                candidates.add(d to sqrt(strength*step))
                            }
                            val selected=ArrayList<Int>()
                            val ranked=candidates.sortedByDescending { (d,strength) -> strength*exp(-.5*(d/(radius*.65)).pow(2)) }
                            for((best,strength) in ranked) {
                                if(selected.any { abs(it-best)<3 }) continue
                                selected.add(best)
                                val left=value(best-1.0);val mid=value(best.toDouble());val right=value(best+1.0)
                                val denominator=left-2*mid+right
                                val delta=if(abs(denominator)>1e-6) (.5*(left-right)/denominator).coerceIn(-.75,.75) else 0.0
                                samples.add(ProfileEdgeSample(ScanPoint(p.x+nx*(best+delta),p.y+ny*(best+delta)),groupIndex*6+localIndex,strength))
                                if(selected.size==6) break
                            }
                        }
                    } finally { if(!patch.isRecycled) patch.recycle();rgba.release();gray.release();gx.release();gy.release() }
                }
                val reference=ScanGeometry.lineThrough(a,b)!!
                val midpoint=ScanPoint((a.x+b.x)/2,(a.y+b.y)/2)
                val fitted=ScanGeometry.robustProfileLine(samples,96,reference,midpoint,radius)
                val angle=fitted?.let { acos(abs(it.nx*reference.nx+it.ny*reference.ny).coerceIn(0.0,1.0))*180/PI } ?: 180.0
                val usable=fitted!=null && fitted.inlierFraction>=.85 && fitted.residualPx<=2.7 &&
                    angle<=7.5 && fitted.distance(midpoint)<=radius*1.15
                if(usable) { lines.add(fitted!!);accepted++;residuals.add(fitted.residualPx);fractions.add(fitted.inlierFraction) }
                else { lines.add(reference);residuals.add(-1.0);fractions.add(fitted?.inlierFraction ?: 0.0) }
            }
        } finally { decoder.recycle() }
        val corners=(0..3).map { ScanGeometry.intersection(lines[(it+3)%4],lines[it]) ?: raw[it] }
        val normalized=corners.map { info.upright(ScanPoint((it.x/(info.rawWidth-1)).coerceIn(0.0,1.0),(it.y/(info.rawHeight-1)).coerceIn(0.0,1.0))) }
        val candidate=initial.copy(points=normalized,origin="native-edge-refinement")
        val plausible=candidate.valid() && candidate.area()/initial.area() in .75..1.3 &&
            candidate.points.zip(initial.points).all { (a,b) -> a.distance(b)<.10 }
        // Mixing fitted and model edges magnified corner errors. If a physical
        // edge is uncertain, retain the coherent model polygon and require review.
        val chosen=if(plausible && accepted==4) candidate else initial
        val padding=6.0
        val safe=chosen.copy(points=ScanGeometry.padded(chosen.points,info.width,info.height,padding))
        return EdgeRefinement(safe,accepted,residuals,fractions,(System.nanoTime()-started)/1_000_000,
            accepted<4 || !plausible || initial.confidence<.72,chosen,padding)
    }
}
