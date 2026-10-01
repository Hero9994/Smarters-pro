package app.masahati.mobile.scanner

import android.graphics.*
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import app.masahati.mobile.OpenCvDocumentRectifier
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.core.Point
import android.graphics.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

data class ScanImageInfo(val rawWidth: Int,val rawHeight: Int,val orientation: Int=1,val turns: Int=0) {
    val swapped get()=(orientation in 5..8) xor (turns.mod(2)==1)
    val width get()=if(swapped) rawHeight else rawWidth
    val height get()=if(swapped) rawWidth else rawHeight
    fun upright(raw: ScanPoint): ScanPoint {
        val (x,y)=raw
        var p=when(orientation) {
            2->ScanPoint(1-x,y);3->ScanPoint(1-x,1-y);4->ScanPoint(x,1-y);5->ScanPoint(y,x)
            6->ScanPoint(1-y,x);7->ScanPoint(1-y,1-x);8->ScanPoint(y,1-x);else->raw }
        repeat(turns.mod(4)) { p=ScanPoint(1-p.y,p.x) };return p
    }
    fun raw(upright: ScanPoint): ScanPoint {
        var p=upright;repeat(turns.mod(4)) { p=ScanPoint(p.y,1-p.x) }
        val (x,y)=p
        return when(orientation) {
            2->ScanPoint(1-x,y);3->ScanPoint(1-x,1-y);4->ScanPoint(x,1-y);5->ScanPoint(y,x)
            6->ScanPoint(y,1-x);7->ScanPoint(1-y,1-x);8->ScanPoint(1-y,x);else->p }
    }
}
object ScanSourceImage {
    fun info(file: File,turns: Int=0): ScanImageInfo {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "تعذر قراءة الصورة" }
        require(bounds.outWidth.toLong()*bounds.outHeight<=160_000_000) { "أبعاد الصورة أكبر من الحد الآمن" }
        val orientation=runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        return ScanImageInfo(bounds.outWidth,bounds.outHeight,orientation.coerceIn(1,8),turns.mod(4))
    }
    fun preview(file: File,turns: Int=0,maxSide: Int=1800): Bitmap {
        val info=info(file,turns);var sample=1
        while(max(info.rawWidth,info.rawHeight)/sample>maxSide) sample*=2
        val bitmap=BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inMutable=true }) ?: error("تعذر فتح الصورة")
        if(info.orientation==1 && turns.mod(4)==0) return bitmap
        val w=if(info.swapped) bitmap.height else bitmap.width;val h=if(info.swapped) bitmap.width else bitmap.height
        var output: Bitmap?=null
        try {
            output=createBitmap(w,h)
            val corners=listOf(ScanPoint(0.0,0.0),ScanPoint(1.0,0.0),ScanPoint(1.0,1.0),ScanPoint(0.0,1.0))
            val from=corners.flatMap { listOf((it.x*bitmap.width).toFloat(),(it.y*bitmap.height).toFloat()) }.toFloatArray()
            val to=corners.map(info::upright).flatMap { listOf((it.x*w).toFloat(),(it.y*h).toFloat()) }.toFloatArray()
            val matrix=Matrix();check(matrix.setPolyToPoly(from,0,to,0,4))
            Canvas(output).drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG));return output
        } catch(error: Throwable) { output?.recycle();throw error } finally { bitmap.recycle() }
    }
    /** Decode bounded NATIVE-resolution source regions, not a full 12–50 MP bitmap.
     * Homography is applied directly to original JPEG pixels, including all EXIF orientations.
     */
    @Suppress("DEPRECATION")
    fun perspective(file: File,quad: DocumentQuad,turns: Int=0,maxSide: Int=4200,maxPixels: Int=memoryPixelBudget(),deskewDegrees: Double=0.0,paperRatio: Double?=null): Bitmap {
        require(quad.valid()) { "زوايا الورقة غير صالحة" };check(OpenCvDocumentRectifier.isAvailable())
        val info=info(file,turns)
        var (outW,outH)=ScanGeometry.outputSize(quad.points,info.width,info.height,maxSide)
        if(paperRatio!=null) {
            require(paperRatio.isFinite() && paperRatio in .03..35.0)
            val area=outW.toDouble()*outH
            outW=max(32,sqrt(area*paperRatio).roundToInt());outH=max(32,sqrt(area/paperRatio).roundToInt())
            val limit=min(1.0,maxSide.toDouble()/max(outW,outH))
            outW=max(32,(outW*limit).roundToInt());outH=max(32,(outH*limit).roundToInt())
        }
        val angle=deskewDegrees.coerceIn(-3.0,3.0)*PI/180
        val expandedPixels=(outW*abs(cos(angle))+outH*abs(sin(angle)))*(outH*abs(cos(angle))+outW*abs(sin(angle)))
        val scale=min(1.0,sqrt(maxPixels.toDouble()/expandedPixels))
        outW=max(32,floor(outW*scale).toInt());outH=max(32,floor(outH*scale).toInt())
        var pageW=outW;var pageH=outH
        outW=ceil(pageW*abs(cos(angle))+pageH*abs(sin(angle))).toInt()
        outH=ceil(pageH*abs(cos(angle))+pageW*abs(sin(angle))).toInt()
        while(outW.toLong()*outH>maxPixels) {
            require(pageW>32 || pageH>32) { "حد الذاكرة أصغر من أبعاد الصفحة" }
            val adjustment=sqrt(maxPixels.toDouble()/(outW.toDouble()*outH))
            val newW=max(32,floor(pageW*adjustment).toInt());val newH=max(32,floor(pageH*adjustment).toInt())
            if(newW==pageW && newH==pageH) {
                if(pageW>=pageH && pageW>32) pageW-- else pageH--
            } else { pageW=newW;pageH=newH }
            outW=ceil(pageW*abs(cos(angle))+pageH*abs(sin(angle))).toInt()
            outH=ceil(pageH*abs(cos(angle))+pageW*abs(sin(angle))).toInt()
        }
        fun target(x: Double,y: Double): Point {
            val cx=x-(pageW-1)/2.0;val cy=y-(pageH-1)/2.0
            return Point(cx*cos(angle)-cy*sin(angle)+(outW-1)/2.0,cx*sin(angle)+cy*cos(angle)+(outH-1)/2.0)
        }
        val points=quad.points.map(info::raw)
        val from=MatOfPoint2f(*points.map { Point(it.x*(info.rawWidth-1),it.y*(info.rawHeight-1)) }.toTypedArray())
        val to=MatOfPoint2f(target(0.0,0.0),target((pageW-1).toDouble(),0.0),target((pageW-1).toDouble(),(pageH-1).toDouble()),target(0.0,(pageH-1).toDouble()))
        val homography=Imgproc.getPerspectiveTransform(from,to);val inverse=homography.inv()
        var decoder: BitmapRegionDecoder?=null;var result: Bitmap?=null
        try {
            val inv=DoubleArray(9);inverse.get(0,0,inv);val coefficients=DoubleArray(9);homography.get(0,0,coefficients)
            decoder=BitmapRegionDecoder.newInstance(file.absolutePath,false) ?: error("تعذر فتح مناطق الصورة")
            result=createBitmap(outW,outH);val canvas=Canvas(result);canvas.drawColor(Color.WHITE)
            if(abs(angle)>1e-8) {
                val clipping=Path();to.toArray().forEachIndexed { i,p ->
                    if(i==0) clipping.moveTo(p.x.toFloat(),p.y.toFloat()) else clipping.lineTo(p.x.toFloat(),p.y.toFloat()) }
                clipping.close();canvas.clipPath(clipping)
            }
            fun source(x: Double,y: Double): ScanPoint {
                val d=inv[6]*x+inv[7]*y+inv[8];require(abs(d)>1e-9)
                return ScanPoint((inv[0]*x+inv[1]*y+inv[2])/d,(inv[3]*x+inv[4]*y+inv[5])/d)
            }
            // A small analysis output can map one output tile onto millions of
            // source pixels. Subdivide in OUTPUT space before decoding its ROI.
            fun renderTile(x: Int,y: Int,w: Int,h: Int) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                val corners=listOf(source(x.toDouble(),y.toDouble()),source((x+w).toDouble(),y.toDouble()),
                    source((x+w).toDouble(),(y+h).toDouble()),source(x.toDouble(),(y+h).toDouble()))
                val rect=Rect(max(0,floor(corners.minOf { it.x }).toInt()-4),max(0,floor(corners.minOf { it.y }).toInt()-4),
                    min(info.rawWidth,ceil(corners.maxOf { it.x }).toInt()+5),min(info.rawHeight,ceil(corners.maxOf { it.y }).toInt()+5))
                if(rect.width()<=0 || rect.height()<=0) return
                if(rect.width().toLong()*rect.height()>4_000_000) {
                    require(w>16 || h>16) { "زاوية التصوير شديدة؛ عدّل الزوايا أو أعد التصوير" }
                    if(w>=h && w>16) {
                        val first=w/2;renderTile(x,y,first,h);renderTile(x+first,y,w-first,h)
                    } else {
                        val first=h/2;renderTile(x,y,w,first);renderTile(x,y+first,w,h-first)
                    }
                    return
                }
                val patch=decoder.decodeRegion(rect,BitmapFactory.Options().apply { inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: error("تعذر قراءة جزء من الصورة")
                val src=Mat();val dst=Mat();val transform=Mat(3,3,CvType.CV_64F);var tile: Bitmap?=null
                try {
                    Utils.bitmapToMat(patch,src);patch.recycle()
                    val a=coefficients.copyOf()
                    for(row in 0..2) a[row*3+2]=coefficients[row*3]*rect.left+coefficients[row*3+1]*rect.top+coefficients[row*3+2]
                    for(col in 0..2) { a[col]-=x*a[6+col];a[3+col]-=y*a[6+col] }
                    transform.put(0,0,*a)
                    Imgproc.warpPerspective(src,dst,transform,Size(w.toDouble(),h.toDouble()),Imgproc.INTER_CUBIC,Core.BORDER_CONSTANT,Scalar(255.0,255.0,255.0,255.0))
                    tile=createBitmap(w,h);Utils.matToBitmap(dst,tile);canvas.drawBitmap(tile,x.toFloat(),y.toFloat(),null)
                } finally { if(!patch.isRecycled) patch.recycle();tile?.recycle();src.release();dst.release();transform.release() }
            }
            for(y in 0 until outH step 256) for(x in 0 until outW step 768) {
                renderTile(x,y,min(768,outW-x),min(256,outH-y))
            }
            return result
        } catch(error: Throwable) { result?.recycle();throw error }
        finally { decoder?.recycle();from.release();to.release();homography.release();inverse.release() }
    }
    fun memoryPixelBudget(): Int {
        val runtime=Runtime.getRuntime();val free=runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())
        require(free>=32_000_000) { "الذاكرة مشغولة؛ أغلق التطبيقات الأخرى وأعد المحاولة" }
        return (free/24).coerceIn(600_000L,7_000_000L).toInt()
    }
}
