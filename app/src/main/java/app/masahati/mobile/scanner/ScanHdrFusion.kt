package app.masahati.mobile.scanner

import android.graphics.Bitmap
import androidx.core.graphics.scale
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

/** The OEM performs multi-frame HDR; we never fabricate document pixels.
 * Registration and ghosting checks compare its JPEG to the separately saved
 * normal exposure. Only bounded previews and tiled native-source warps are used.
 */
object ScanHdrFusion {
    data class Aligned(val image: Bitmap,val matches: Int,val inlierFraction: Double)
    data class Ghosting(val lostInk: Double,val newInk: Double) {
        fun acceptable()=lostInk<=.08 && newInk<=.12
    }
    fun align(normal: File,hdr: File,quad: DocumentQuad,turns: Int,base: Bitmap,
        deskew: Double,paperRatio: Double?): Aligned {
        val a=ScanSourceImage.preview(normal,turns,1280)
        val b=try { ScanSourceImage.preview(hdr,turns,1280) } catch(error: Throwable) { a.recycle();throw error }
        val ar=Mat();val br=Mat();val ag=Mat();val bg=Mat();val mask=Mat()
        val ka=MatOfKeyPoint();val kb=MatOfKeyPoint();val da=Mat();val db=Mat();val empty=Mat()
        val pointsA=MatOfPoint2f();val pointsB=MatOfPoint2f();val inliers=Mat();val corners=MatOfPoint2f();val mapped=MatOfPoint2f()
        val matches=ArrayList<MatOfDMatch>();val orb=ORB.create(1800);val matcher=BFMatcher.create(Core.NORM_HAMMING,false)
        var homography: Mat?=null
        try {
            Utils.bitmapToMat(a,ar);Utils.bitmapToMat(b,br)
            Imgproc.cvtColor(ar,ag,Imgproc.COLOR_RGBA2GRAY);Imgproc.cvtColor(br,bg,Imgproc.COLOR_RGBA2GRAY)
            mask.create(a.height,a.width,CvType.CV_8U);mask.setTo(Scalar(0.0))
            val polygon=MatOfPoint(*quad.points.map { Point(it.x*(a.width-1),it.y*(a.height-1)) }.toTypedArray())
            try { Imgproc.fillConvexPoly(mask,polygon,Scalar(255.0)) } finally { polygon.release() }
            orb.detectAndCompute(ag,mask,ka,da);orb.detectAndCompute(bg,empty,kb,db)
            require(!da.empty() && !db.empty()) { "لم نتمكن من محاذاة HDR؛ بقي الأصل" }
            matcher.knnMatch(da,db,matches,2)
            val chosen=matches.mapNotNull { m -> val pair=m.toArray()
                pair.firstOrNull()?.takeIf { pair.size==2 && it.distance<pair[1].distance*.72 } }
            require(chosen.size>=32) { "تفاصيل المحاذاة غير كافية؛ بقي الأصل" }
            val source=ka.toArray();val target=kb.toArray()
            pointsA.fromArray(*chosen.map { source[it.queryIdx].pt }.toTypedArray())
            pointsB.fromArray(*chosen.map { target[it.trainIdx].pt }.toTypedArray())
            val transform=Calib3d.findHomography(pointsA,pointsB,Calib3d.RANSAC,2.5,inliers,2000,.995)
            homography=transform;require(!transform.empty()) { "تعذرت محاذاة HDR" }
            val support=Core.countNonZero(inliers).toDouble()/chosen.size
            require(support>=.75 && Core.countNonZero(inliers)>=24) { "تحركت الورقة بين اللقطتين؛ بقي الأصل" }
            corners.fromArray(*quad.points.map { Point(it.x*(a.width-1),it.y*(a.height-1)) }.toTypedArray())
            Core.perspectiveTransform(corners,mapped,transform)
            val aligned=DocumentQuad(mapped.toArray().map { ScanPoint(it.x/(b.width-1),it.y/(b.height-1)) },origin="HDR-registration")
            require(aligned.valid() && aligned.points.zip(quad.points).all { (p,q) -> p.distance(q)<.12 }) {
                "تغير الإطار أو اختفت حافة بين اللقطتين؛ بقي الأصل" }
            val info=ScanSourceImage.info(normal,turns)
            val size=ScanGeometry.outputSize(quad.points,info.width,info.height,4200)
            val ratio=paperRatio ?: size.first.toDouble()/size.second
            val image=ScanSourceImage.perspective(hdr,aligned,turns,max(base.width,base.height),
                base.width*base.height,deskew,ratio)
            if(image.width==base.width && image.height==base.height) return Aligned(image,chosen.size,support)
            try { return Aligned(image.scale(base.width,base.height),chosen.size,support) } finally { image.recycle() }
        } finally {
            a.recycle();b.recycle();orb.clear();matcher.clear();matches.forEach { it.release() }
            listOf(ar,br,ag,bg,mask,ka,kb,da,db,empty,pointsA,pointsB,inliers,corners,mapped).forEach { it.release() }
            homography?.release()
        }
    }
    /** Symmetric structural check: reject missing ink and newly doubled ink.
     * This detects gross ghosting, not every subpixel artifact. OCR/QR/detail
     * validators additionally protect content before adopting the candidate.
     */
    fun ghosting(before: Bitmap,after: Bitmap): Ghosting {
        val factor=min(1.0,1100.0/max(before.width,before.height))
        val w=max(32,(before.width*factor).roundToInt());val h=max(32,(before.height*factor).roundToInt())
        val a=before.scale(w,h);val b=after.scale(w,h)
        val ar=Mat();val br=Mat();val ag=Mat();val bg=Mat();val ad=Mat();val bd=Mat();val missing=Mat();val added=Mat();val valid=Mat()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(5.0,5.0))
        try {
            Utils.bitmapToMat(a,ar);Utils.bitmapToMat(b,br)
            Imgproc.cvtColor(ar,ag,Imgproc.COLOR_RGBA2GRAY);Imgproc.cvtColor(br,bg,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(ag,ag,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY_INV,31,15.0)
            Imgproc.adaptiveThreshold(bg,bg,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY_INV,31,15.0)
            valid.create(h,w,CvType.CV_8U);valid.setTo(Scalar(0.0))
            Imgproc.rectangle(valid,Point(w*.02,h*.02),Point(w*.98,h*.98),Scalar(255.0),-1)
            Core.bitwise_and(ag,valid,ag);Core.bitwise_and(bg,valid,bg)
            Imgproc.dilate(ag,ad,kernel);Imgproc.dilate(bg,bd,kernel)
            Core.bitwise_not(bd,missing);Core.bitwise_and(ag,missing,missing)
            Core.bitwise_not(ad,added);Core.bitwise_and(bg,added,added)
            return Ghosting(Core.countNonZero(missing).toDouble()/Core.countNonZero(ag).coerceAtLeast(1),
                Core.countNonZero(added).toDouble()/Core.countNonZero(bg).coerceAtLeast(1))
        } finally {
            if(a!==before) a.recycle();if(b!==after) b.recycle()
            listOf(ar,br,ag,bg,ad,bd,missing,added,valid,kernel).forEach { it.release() }
        }
    }
}

/** Conservative device policy. Unknown/slow latency skips HDR rather than
 * blocking the ordinary shutter. ZSL is never described as HDR fusion.
 */
object ScanHdrPolicy {
    fun shouldAttempt(supported: Boolean,maximumLatencyMs: Long?,stable: Boolean,quality: CaptureQuality?,ageMs: Long): Boolean =
        supported && maximumLatencyMs!=null && maximumLatencyMs in 1..1800 && stable && ageMs in 0..500 &&
            quality!=null && quality.focused && quality.sharpness>=42 && quality.darkFraction<.32 &&
            (quality.shadowSeverity>.45 || quality.clippedFraction>.01 || quality.glareFraction>.007)
}
