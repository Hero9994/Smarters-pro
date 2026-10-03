package app.masahati.mobile.scanner

import android.graphics.Bitmap
import androidx.core.graphics.scale
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class DewarpCoverage(val inkPixels: Int,val missingPixels: Int,val damagedComponents: Int) {
    val missingFraction: Double get()=missingPixels.toDouble()/inkPixels.coerceAtLeast(1)
    fun acceptable()=missingFraction<=.005 && damagedComponents==0
}

/** Validate source-content coverage BEFORE allocating/remapping a dewarped page.
 * OCR cannot see a lost signature or a second copy of text outside its 40 lines.
 * This bounded analysis does not claim to detect every single native-resolution dot.
 */
object ScanDewarpGuard {
    fun coverage(source: Bitmap,grid: FloatArray): DewarpCoverage {
        require(grid.size==2790 && grid.all { it.isFinite() && abs(it)<=1.15 })
        val factor=min(1.0,1400.0/max(source.width,source.height))
        val image=source.scale(max(32,(source.width*factor).roundToInt()),max(32,(source.height*factor).roundToInt()))
        val rgba=Mat();val gray=Mat();val paper=Mat();val mask=Mat();val covered=Mat();val labels=Mat();val stats=Mat();val centers=Mat()
        val polygon=MatOfPoint();val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(31.0,31.0))
        try {
            Utils.bitmapToMat(image,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.morphologyEx(gray,paper,Imgproc.MORPH_CLOSE,kernel)
            val pixels=ByteArray(image.width*image.height*4);rgba.get(0,0,pixels)
            val lum=ByteArray(image.width*image.height);val background=ByteArray(lum.size)
            gray.get(0,0,lum);paper.get(0,0,background)
            val content=ByteArray(lum.size) { i ->
                val v=lum[i].toInt() and 255;val bg=background[i].toInt() and 255
                val r=pixels[i*4].toInt() and 255;val g=pixels[i*4+1].toInt() and 255;val b=pixels[i*4+2].toInt() and 255
                if((bg-v>=10 && v<bg*.88) || (maxOf(r,g,b)-minOf(r,g,b)>45 && v<230)) 255.toByte() else 0
            }
            mask.create(image.height,image.width,CvType.CV_8U);mask.put(0,0,content)
            covered.create(image.height,image.width,CvType.CV_8U);covered.setTo(Scalar(0.0))
            fun point(i: Int)=Point((grid[i]+1)*.5*(image.width-1),(grid[1395+i]+1)*.5*(image.height-1))
            // Use every source mesh cell, not just a rectangular bounding box.
            for(y in 0 until 44) for(x in 0 until 30) {
                val i=y*31+x;polygon.fromArray(point(i),point(i+1),point(i+32),point(i+31))
                Imgproc.fillConvexPoly(covered,polygon,Scalar(255.0))
            }
            val coverage=ByteArray(content.size);covered.get(0,0,coverage)
            val count=Imgproc.connectedComponentsWithStats(mask,labels,stats,centers,8,CvType.CV_32S)
            val component=IntArray(content.size);labels.get(0,0,component)
            val missing=IntArray(count);var ink=0;var lost=0
            for(i in content.indices) if(content[i].toInt()!=0) {
                ink++;if(coverage[i].toInt()==0) { lost++;missing[component[i]]++ }
            }
            var damaged=0
            for(i in 1 until count) {
                val area=stats.get(i,Imgproc.CC_STAT_AREA)[0]
                if(area>=3 && missing[i]>=2 && missing[i]/area>.02) damaged++
            }
            return DewarpCoverage(ink,lost,damaged)
        } finally {
            if(image!==source) image.recycle()
            listOf(rgba,gray,paper,mask,covered,labels,stats,centers,polygon,kernel).forEach { it.release() }
        }
    }
}
