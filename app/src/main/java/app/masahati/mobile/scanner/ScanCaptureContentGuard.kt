package app.masahati.mobile.scanner

import android.graphics.Bitmap
import androidx.core.graphics.scale
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Independent real captures can make a blurred ink halo narrower. Compare local
 * source components with a two-pixel registration neighborhood, not exact tonal
 * pixels. This never changes either capture or weakens the filter quality guard.
 * OCR/QR, sharpness/exposure and symmetric ghosting are separate mandatory gates.
 * Scope: bounded 1400-side preview, not proof for every native-resolution pixel.
 */
object ScanCaptureContentGuard {
    fun reasons(before: Bitmap,after: Bitmap): List<String> {
        require(before.width==after.width && before.height==after.height)
        val factor=min(1.0,1400.0/max(before.width,before.height))
        val w=max(32,(before.width*factor).roundToInt());val h=max(32,(before.height*factor).roundToInt())
        val a=before.scale(w,h);val b=after.scale(w,h)
        val ar=Mat();val br=Mat();val ag=Mat();val bg=Mat();val ah=Mat();val bh=Mat()
        val ab=Mat();val bb=Mat();val mask=Mat(h,w,CvType.CV_8U);val candidate=Mat(h,w,CvType.CV_8U)
        val dilation=Mat();val labels=Mat();val stats=Mat();val centroids=Mat()
        val paper=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(31.0,31.0))
        val neighborhood=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(5.0,5.0))
        try {
            Utils.bitmapToMat(a,ar);Utils.bitmapToMat(b,br)
            Imgproc.cvtColor(ar,ag,Imgproc.COLOR_RGBA2GRAY);Imgproc.cvtColor(br,bg,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(ar,ar,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(br,br,Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(ar,ah,Imgproc.COLOR_RGB2HSV);Imgproc.cvtColor(br,bh,Imgproc.COLOR_RGB2HSV)
            // Closing is used as a paper GUIDE only. Neither source is cleaned.
            Imgproc.morphologyEx(ag,ab,Imgproc.MORPH_CLOSE,paper)
            Imgproc.morphologyEx(bg,bb,Imgproc.MORPH_CLOSE,paper)
            val n=w*h;val av=ByteArray(n);val bv=ByteArray(n);val ap=ByteArray(n);val bp=ByteArray(n)
            val ac=ByteArray(n*3);val bc=ByteArray(n*3)
            ag.get(0,0,av);bg.get(0,0,bv);ab.get(0,0,ap);bb.get(0,0,bp)
            ah.get(0,0,ac);bh.get(0,0,bc)
            val sourceMask=ByteArray(n);val targetMask=ByteArray(n);val covered=ByteArray(n)
            val ids=IntArray(n)
            fun damaged(): Int {
                mask.put(0,0,sourceMask);candidate.put(0,0,targetMask)
                Imgproc.dilate(candidate,dilation,neighborhood);dilation.get(0,0,covered)
                val components=Imgproc.connectedComponentsWithStats(mask,labels,stats,centroids,8,CvType.CV_32S)
                labels.get(0,0,ids)
                val size=IntArray(components);val missing=IntArray(components)
                for(i in 0 until n) if(sourceMask[i].toInt()!=0) {
                    val id=ids[i];size[id]++;if(covered[i].toInt()==0) missing[id]++
                }
                return (1 until components).count { size[it]>=2 && missing[it]>=1 &&
                    missing[it].toDouble()/size[it]>.02 }
            }
            for(i in 0 until n) {
                val original=av[i].toInt() and 255;val originalPaper=ap[i].toInt() and 255
                val next=bv[i].toInt() and 255;val nextPaper=bp[i].toInt() and 255
                sourceMask[i]=if(originalPaper-original>=4 && original<originalPaper*.98) -1 else 0
                targetMask[i]=if(nextPaper-next>=4 && next<nextPaper*.98) -1 else 0
            }
            val reasons=mutableListOf<String>()
            if(damaged()>0) reasons.add("فقدت اللقطة الإضافية علامة أو جزءًا من الحبر")
            for(bin in 0 until 12) {
                var hasColor=false
                val center=bin*15+7.0
                for(i in 0 until n) {
                    val j=i*3;val hue=ac[j].toInt() and 255
                    val colored=(ac[j+1].toInt() and 255)>=40 && (ac[j+2].toInt() and 255)<245
                    val selected=colored && hue/15==bin
                    sourceMask[i]=if(selected) -1 else 0;hasColor=hasColor || selected
                    val targetHue=bc[j].toInt() and 255
                    val distance=abs(targetHue-center)
                    targetMask[i]=if((bc[j+1].toInt() and 255)>=40 && (bc[j+2].toInt() and 255)<245 &&
                        min(distance,180-distance)<=10) -1 else 0
                }
                if(hasColor && damaged()>0) {
                    reasons.add("فقدت اللقطة الإضافية جزءًا من لون أو توقيع");break
                }
            }
            return reasons
        } finally {
            if(a!==before) a.recycle();if(b!==after) b.recycle()
            listOf(ar,br,ag,bg,ah,bh,ab,bb,mask,candidate,dilation,labels,stats,centroids,paper,neighborhood).forEach { it.release() }
        }
    }
}
