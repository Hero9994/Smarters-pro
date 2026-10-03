package app.masahati.mobile.scanner

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.masahati.mobile.OpenCvDocumentRectifier
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class PaperProcessing(val bitmap: Bitmap,val shadowSeverity: Double,val paperFraction: Double,val elapsedMs: Long)

/** Deterministic, tiled LAB illumination normalization. No inpainting, generative
 * restoration, global brightness or default threshold. Dark dots are NEVER removed
 * by size: contrast, local edges, chroma and photo texture protect original content.
 */
object ScanPaperProcessor {
    fun process(source: Bitmap,mode: ScanFilter,strength: Double=1.0): PaperProcessing {
        val started=System.nanoTime()
        if(mode==ScanFilter.ORIGINAL) return PaperProcessing(source,0.0,0.0,0)
        check(OpenCvDocumentRectifier.isAvailable())
        val scale=min(1.0,640.0/max(source.width,source.height))
        val thumb=source.scale(max(1,(source.width*scale).roundToInt()),max(1,(source.height*scale).roundToInt()))
        val rgba=Mat();val rgb=Mat();val lab=Mat();val lum=Mat();val background=Mat();val closed=Mat();val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(29.0,29.0))
        val texture=Mat();val mean=Mat();val squared=Mat();val variance=Mat();val mids=Mat();val midFraction=Mat();val brightFraction=Mat()
        val map: ByteArray;val photoMap: ByteArray;val mw=thumb.width;val mh=thumb.height
        var paperA=0.0;var paperB=0.0
        var severity=0.0
        try {
            Utils.bitmapToMat(thumb,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(rgb,lab,Imgproc.COLOR_RGB2Lab)
            Core.extractChannel(lab,lum,0)
            Imgproc.morphologyEx(lum,closed,Imgproc.MORPH_CLOSE,kernel)
            // Blur across a sharp illumination step invents an intermediate
            // background on both sides, leaving a grey halo and lifting nearby
            // ink unevenly. Smooth the bounded, ink-suppressed map while keeping
            // real illumination steps. The source image itself is never blurred.
            Imgproc.bilateralFilter(closed,background,29,12.0,14.0)
            map=ByteArray(mw*mh);background.get(0,0,map)
            val sorted=map.map { it.toInt() and 255 }.sorted()
            severity=((sorted[(sorted.lastIndex*.9).toInt()]-sorted[(sorted.lastIndex*.1).toInt()])/130.0).coerceIn(0.0,1.0)
            lum.convertTo(texture,CvType.CV_32F);Imgproc.boxFilter(texture,mean,-1,Size(17.0,17.0))
            Core.multiply(texture,texture,squared);Imgproc.boxFilter(squared,squared,-1,Size(17.0,17.0));Core.multiply(mean,mean,variance)
            Core.subtract(squared,variance,variance)
            // A bold paragraph also has high variance and many antialiased
            // midtones. It is not a photograph: there is still abundant local
            // paper between the strokes. Requiring few paper-like bright pixels
            // avoids grey islands behind headings without weakening the content
            // protection applied independently to the original ink pixels.
            val grayBytes=ByteArray(mw*mh);lum.get(0,0,grayBytes)
            val medium=FloatArray(mw*mh) { i -> val v=grayBytes[i].toInt() and 255;val bg=map[i].toInt() and 255
                if(v>bg*.2 && v<bg*.75) 1f else 0f }
            mids.create(mh,mw,CvType.CV_32F);mids.put(0,0,medium);Imgproc.boxFilter(mids,midFraction,-1,Size(17.0,17.0))
            val bright=FloatArray(mw*mh) { i -> if((grayBytes[i].toInt() and 255)>(map[i].toInt() and 255)*.86) 1f else 0f }
            mids.put(0,0,bright);Imgproc.boxFilter(mids,brightFraction,-1,Size(17.0,17.0))
            Imgproc.threshold(brightFraction,brightFraction,.35,255.0,Imgproc.THRESH_BINARY_INV);brightFraction.convertTo(brightFraction,CvType.CV_8U)
            Imgproc.threshold(midFraction,midFraction,.25,255.0,Imgproc.THRESH_BINARY);midFraction.convertTo(midFraction,CvType.CV_8U)
            Imgproc.threshold(variance,variance,850.0,255.0,Imgproc.THRESH_BINARY);variance.convertTo(variance,CvType.CV_8U)
            Core.bitwise_and(variance,midFraction,variance)
            Core.bitwise_and(variance,brightFraction,variance)
            Imgproc.dilate(variance,variance,kernel);photoMap=ByteArray(mw*mh);variance.get(0,0,photoMap)
            if(mode==ScanFilter.CLEAN_WHITE) {
                val colors=ByteArray(mw*mh*3);lab.get(0,0,colors)
                val ah=IntArray(256);val bh=IntArray(256);var samples=0
                for(i in map.indices) {
                    val v=grayBytes[i].toInt() and 255;val bg=map[i].toInt() and 255
                    val aa=colors[i*3+1].toInt() and 255;val bb=colors[i*3+2].toInt() and 255
                    if(photoMap[i].toInt()==0 && bg>=80 && v>=bg*.92 && hypot(aa-128.0,bb-128.0)<=35) {
                        ah[aa]++;bh[bb]++;samples++
                    }
                }
                fun median(histogram: IntArray): Double {
                    var total=0;for(i in histogram.indices) { total+=histogram[i];if(total>=samples/2) return i-128.0 }
                    return 0.0
                }
                // A dominant mild paper tint can be neutralized in the explicit
                // white-paper mode. Photos and distinct stamp/signature colors
                // are excluded; Auto/Photo retain their conservative color policy.
                if(samples>=mw*mh*.35) { paperA=median(ah);paperB=median(bh) }
            }
        } finally {
            if(thumb!==source) thumb.recycle()
            listOf(rgba,rgb,lab,lum,background,closed,kernel,texture,mean,squared,variance,mids,midFraction,brightFraction).forEach { it.release() }
        }
        val w=source.width;val h=source.height;val output=createBitmap(w,h)
        var paperPixels=0L
        val sharpIllumination=map.indices.any { i -> val v=map[i].toInt() and 255
            (i%mw>0 && abs(v-(map[i-1].toInt() and 255))>=16) ||
                (i>=mw && abs(v-(map[i-mw].toInt() and 255))>=16) }
        val guideKernel=if(sharpIllumination) Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,Size(9.0,9.0)) else null
        val contrastLow=if(sharpIllumination) ByteArray(map.size) else null
        val contrastHigh=if(sharpIllumination) ByteArray(map.size) else null
        if(contrastLow!=null && contrastHigh!=null) {
            for(yy in 0 until mh) for(xx in 0 until mw) {
                var low=255;var high=0
                // The interpolation cell spans (x,y)..(x+1,y+1). Include
                // one neighbour around ALL four corners, not just its top-left
                // corner; the real bright plateau may start at x+2 or y+2.
                for(nearY in max(0,yy-1)..min(mh-1,yy+2)) for(nearX in max(0,xx-1)..min(mw-1,xx+2)) {
                    val value=map[nearY*mw+nearX].toInt() and 255
                    low=min(low,value);high=max(high,value)
                }
                contrastLow[yy*mw+xx]=low.toByte();contrastHigh[yy*mw+xx]=high.toByte()
            }
        }
        fun mapValue(x: Int,y: Int,guide: Double): Double {
            val fx=(x.toDouble()/max(1,w-1)*(mw-1)).coerceIn(0.0,(mw-1).toDouble())
            val fy=(y.toDouble()/max(1,h-1)*(mh-1)).coerceIn(0.0,(mh-1).toDouble())
            val x0=fx.toInt();val y0=fy.toInt();val x1=min(x0+1,mw-1);val y1=min(y0+1,mh-1)
            val dx=fx-x0;val dy=fy-y0
            fun at(xx: Int,yy: Int)=map[yy*mw+xx].toInt() and 255
            val v00=at(x0,y0);val v10=at(x1,y0);val v01=at(x0,y1);val v11=at(x1,y1)
            // A downsampled boundary may contain an intermediate pixel. The
            // immediate interpolation cell then has little contrast even though
            // its next neighbour is a genuine illumination step. Look for the
            // two plateaus around all four interpolation corners, not just its
            // top-left corner. Their one-pixel neighbourhoods form a 4x4 union.
            // Precompute these bounds at preview resolution to avoid a full-size
            // neighbourhood scan for every output pixel.
            val low=contrastLow?.let { it[y0*mw+x0].toInt() and 255 } ?: 255
            val high=contrastHigh?.let { it[y0*mw+x0].toInt() and 255 } ?: 0
            if(high-low>=16) {
                val nearest=if(abs(guide-low)<abs(guide-high)) low.toDouble() else high.toDouble()
                if(abs(guide-nearest)<=max(8.0,nearest*.06)) return nearest
            }
            return (v00*(1-dx)+v10*dx)*(1-dy)+(v01*(1-dx)+v11*dx)*dy
        }
        val target=when(mode) { ScanFilter.CLEAN_WHITE->254.0;ScanFilter.CLEAR_TEXT->252.0;ScanFilter.PHOTO->246.0;else->250.0 }
        try {
            for(y in 0 until h step 128) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                val halo=if(mode==ScanFilter.BLACK_WHITE) 16 else if(sharpIllumination) 8 else 2
                val top=max(0,y-halo);val bottom=min(h,y+128+halo);val rows=bottom-top;val pixels=IntArray(w*rows)
                source.getPixels(pixels,0,w,0,top,w,rows)
                val patch=createBitmap(w,rows);patch.setPixels(pixels,0,w,0,0,w,rows)
                val r=Mat();val c=Mat();val l=Mat();val guide=Mat();val result=Mat();var converted: Bitmap?=null
                try {
                    Utils.bitmapToMat(patch,r);patch.recycle();Imgproc.cvtColor(r,c,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(c,l,Imgproc.COLOR_RGB2Lab)
                    val original=ByteArray(w*rows*3);l.get(0,0,original);val changed=original.copyOf()
                    val backgroundGuide=if(guideKernel!=null) {
                        Core.extractChannel(l,guide,0);Imgproc.morphologyEx(guide,guide,Imgproc.MORPH_CLOSE,guideKernel)
                        ByteArray(w*rows).also { guide.get(0,0,it) }
                    } else null
                    fun at(xx: Int,yy: Int)=original[(yy.coerceIn(0,rows-1)*w+xx.coerceIn(0,w-1))*3].toInt() and 255
                    for(yy in 0 until rows) for(x in 0 until w) {
                        val index=(yy*w+x)*3;val v=at(x,yy).toDouble()
                        val localPaper=backgroundGuide?.let { it[yy*w+x].toInt() and 255 }?.toDouble() ?: v
                        val bg=mapValue(x,top+yy,localPaper).coerceAtLeast(45.0)
                        val a=(original[index+1].toInt() and 255)-128;val b=(original[index+2].toInt() and 255)-128
                        val chroma=hypot(a-paperA,b-paperB)
                        val left=at(x-1,yy);val right=at(x+1,yy);val above=at(x,yy-1);val below=at(x,yy+1)
                        val range=max(max(left,right),max(max(above,below),v.toInt()))-
                            min(min(left,right),min(min(above,below),v.toInt()))
                        val photo=photoMap[min(mh-1,(top+yy)*mh/h)*mw+min(mw-1,x*mw/w)].toInt()!=0
                        val ratio=(v/bg).coerceIn(0.0,1.4)
                        val colored=chroma>17
                        val protected=range>=8 || ratio<.86 || colored || photo
                        val gain=(target/bg).coerceIn(.9,2.8)
                        val inkStrength=if(colored || photo) min(strength,.45) else strength
                        var value=v*(1+(gain-1)*inkStrength)
                        if(!protected) {
                            val weight=((ratio-.86)/.12).coerceIn(0.0,1.0)*strength
                            value=value*(1-weight)+target*weight
                            changed[index+1]=(128+(a*(1-weight*.9))).roundToInt().coerceIn(0,255).toByte()
                            changed[index+2]=(128+(b*(1-weight*.9))).roundToInt().coerceIn(0,255).toByte()
                            if(yy+top in y until min(h,y+128)) paperPixels++
                        } else if(mode==ScanFilter.CLEAR_TEXT && !colored && !photo) {
                            value=target*(value/target).coerceIn(0.0,1.0).pow(1+.12*strength)
                        }
                        changed[index]=value.roundToInt().coerceIn(0,255).toByte()
                    }
                    l.put(0,0,changed)
                    if(mode==ScanFilter.BLACK_WHITE) {
                        val gray=Mat();val f=Mat();val meanLocal=Mat();val sq=Mat();val std=Mat()
                        try {
                            Core.extractChannel(l,gray,0);gray.convertTo(f,CvType.CV_32F)
                            Imgproc.boxFilter(f,meanLocal,-1,Size(31.0,31.0));Core.multiply(f,f,sq);Imgproc.boxFilter(sq,sq,-1,Size(31.0,31.0))
                            Core.multiply(meanLocal,meanLocal,std);Core.subtract(sq,std,std);Core.max(std,Scalar(0.0),std);Core.sqrt(std,std)
                            val values=FloatArray(w*rows);val means=FloatArray(w*rows);val deviations=FloatArray(w*rows)
                            f.get(0,0,values);meanLocal.get(0,0,means);std.get(0,0,deviations)
                            val binary=ByteArray(w*rows) { i -> if(values[i]>means[i]*(1+.23f*(deviations[i]/128-1))) 255.toByte() else 0 }
                            gray.put(0,0,binary);Imgproc.cvtColor(gray,result,Imgproc.COLOR_GRAY2RGBA)
                        } finally { listOf(gray,f,meanLocal,sq,std).forEach { it.release() } }
                    } else { Imgproc.cvtColor(l,c,Imgproc.COLOR_Lab2RGB);Imgproc.cvtColor(c,result,Imgproc.COLOR_RGB2RGBA) }
                    converted=createBitmap(w,rows);Utils.matToBitmap(result,converted)
                    val middle=IntArray(w*min(128,h-y));converted.getPixels(middle,0,w,0,y-top,w,min(128,h-y))
                    output.setPixels(middle,0,w,0,y,w,min(128,h-y))
                } finally { if(!patch.isRecycled) patch.recycle();converted?.recycle();r.release();c.release();l.release();guide.release();result.release() }
            }
            return PaperProcessing(output,severity,paperPixels.toDouble()/(w.toLong()*h),(System.nanoTime()-started)/1_000_000)
        } catch(error: Throwable) { output.recycle();throw error }
        finally { guideKernel?.release() }
    }
}
