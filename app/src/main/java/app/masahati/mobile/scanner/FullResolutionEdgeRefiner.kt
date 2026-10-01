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
    val inlierFractions: List<Double>,val elapsedMs: Long,val needsManualReview: Boolean)

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
                val nx=-(b.y-a.y)/length;val ny=(b.x-a.x)/length;val samples=ArrayList<EdgeSample>()
                val positions=(0 until 96).map { i -> val t=.025+i/95.0*.95
                    ScanPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t) }
                for(group in positions.chunked(6)) {
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
                        for(p in group) {
                            fun index(d: Double): Int {
                                val x=(p.x+nx*d-rect.left).roundToInt();val y=(p.y+ny*d-rect.top).roundToInt()
                                return if(x in 1 until rect.width()-1 && y in 1 until rect.height()-1) y*rect.width()+x else -1 }
                            fun value(d: Double): Double { val i=index(d);return if(i<0) 0.0 else abs(xs[i]*nx+ys[i]*ny) }
                            var best=0;var score=0.0;val range=radius.roundToInt()
                            for(d in -range+2 until range-2) {
                                val strength=value(d.toDouble())
                                if(strength<24 || strength<value(d-1.0) || strength<value(d+1.0)) continue
                                val before=index(d-5.0);val after=index(d+5.0);if(before<0 || after<0) continue
                                val step=abs((lum[before].toInt() and 255)-(lum[after].toInt() and 255));if(step<7) continue
                                // Sustained steps suppress thin printed rectangles; localization supplies the prior.
                                val candidate=sqrt(strength)*sqrt(step.toDouble())*exp(-.5*(d/(radius*.65)).pow(2))
                                if(candidate>score) { score=candidate;best=d }
                            }
                            if(score>12) {
                                val left=value(best-1.0);val mid=value(best.toDouble());val right=value(best+1.0)
                                val denominator=left-2*mid+right
                                val delta=if(abs(denominator)>1e-6) (.5*(left-right)/denominator).coerceIn(-.75,.75) else 0.0
                                samples.add(EdgeSample(ScanPoint(p.x+nx*(best+delta),p.y+ny*(best+delta)),min(2.0,score/70)))
                            }
                        }
                    } finally { if(!patch.isRecycled) patch.recycle();rgba.release();gray.release();gx.release();gy.release() }
                }
                val reference=ScanGeometry.lineThrough(a,b)!!;val fitted=ScanGeometry.robustLine(samples)
                val angle=fitted?.let { acos(abs(it.nx*reference.nx+it.ny*reference.ny).coerceIn(0.0,1.0))*180/PI } ?: 180.0
                val midpoint=ScanPoint((a.x+b.x)/2,(a.y+b.y)/2)
                val usable=fitted!=null && samples.size>=40 && fitted.inlierFraction>=.60 && fitted.residualPx<=2.7 &&
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
        val chosen=if(plausible) candidate else initial
        val safe=chosen.copy(points=ScanGeometry.padded(chosen.points,info.width,info.height,if(accepted==4 && plausible) 3.0 else 6.0))
        return EdgeRefinement(safe,accepted,residuals,fractions,(System.nanoTime()-started)/1_000_000,
            accepted<4 || !plausible || initial.confidence<.72)
    }
}
