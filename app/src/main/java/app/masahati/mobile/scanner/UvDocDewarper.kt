package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import ai.onnxruntime.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.*

data class DewarpResult(val image: Bitmap,val elapsedMs: Long,val gridMinimumJacobian: Double,
    val coverage: DewarpCoverage)
/** MIT UVDoc geometry-only grid; all pixels come from the real capture.
 * Align-corners interpolation and bounded source ROI tiles, no full-size maps.
 */
class UvDocDewarper(context: Context): AutoCloseable {
    private val context=context.applicationContext
    private val environment=OrtEnvironment.getEnvironment()
    private var session: OrtSession?=null
    @Synchronized fun dewarp(source: Bitmap): DewarpResult {
        val started=System.nanoTime()
        val free=Runtime.getRuntime().let { it.maxMemory()-(it.totalMemory()-it.freeMemory()) }
        require(free>96_000_000+source.width.toLong()*source.height*4) { "الذاكرة لا تكفي للتسطيح المتقدم؛ بقي القص العادي محفوظًا" }
        val resized=source.scale(488,712)
        val values=FloatArray(488*712*3);val pixels=IntArray(488*712)
        resized.getPixels(pixels,0,488,0,0,488,712);if(resized!==source) resized.recycle()
        pixels.forEachIndexed { i,p -> values[i]=Color.red(p)/255f;values[i+488*712]=Color.green(p)/255f;values[i+2*488*712]=Color.blue(p)/255f }
        val runner=session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            environment.createSession(ScanModelAssets.extract(context,"uvdoc-grid.onnx",SHA).absolutePath,options)
        }.also { session=it }
        val grid=OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),longArrayOf(1,3,712,488)).use { input ->
            runner.run(mapOf("image" to input)).use { DocQuadDetector.floats(it[0] as OnnxTensor) } }
        require(grid.size==2*45*31 && grid.all { it.isFinite() && abs(it)<=1.15 }) { "شبكة التسطيح خرجت عن حدود الورقة" }
        var minimum=Double.POSITIVE_INFINITY
        for(y in 0 until 44) for(x in 0 until 30) {
            val i=y*31+x
            // A bilinear cell can fold at the opposite corner even when its
            // top-left Jacobian is positive. Check all four local orientations.
            for((a,b,c) in listOf(Triple(i,i+1,i+31),Triple(i+1,i+32,i),Triple(i+32,i+31,i+1),Triple(i+31,i,i+32))) {
                val dx=grid[b]-grid[a];val dy=grid[c]-grid[a]
                val vx=grid[1395+b]-grid[1395+a];val vy=grid[1395+c]-grid[1395+a]
                val jac=(dx*vy-dy*vx).toDouble();minimum=min(minimum,jac)
                require(jac>0.000015 && jac<.05) { "التسطيح قد يشوه جزءًا من الورقة؛ احتفظنا بالقص العادي" }
            }
        }
        val coverage=ScanDewarpGuard.coverage(source,grid)
        require(coverage.acceptable()) { "التسطيح المتقدم قد يفقد حبرًا قرب الحواف؛ أبقينا القص العادي" }
        fun coordinate(x: Int,y: Int,channel: Int): Float {
            val gx=x.toDouble()/(source.width-1)*30;val gy=y.toDouble()/(source.height-1)*44
            val ix=floor(gx).toInt().coerceAtMost(29);val iy=floor(gy).toInt().coerceAtMost(43)
            val fx=gx-ix;val fy=gy-iy;val base=channel*1395+iy*31+ix
            val value=(grid[base]*(1-fx)+grid[base+1]*fx)*(1-fy)+(grid[base+31]*(1-fx)+grid[base+32]*fx)*fy
            return ((value+1)/2*(if(channel==0) source.width-1 else source.height-1)).toFloat()
        }
        val output=createBitmap(source.width,source.height);val canvas=Canvas(output);canvas.drawColor(Color.WHITE)
        try {
            for(y in 0 until source.height step 192) for(x in 0 until source.width step 768) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                val w=min(768,source.width-x);val h=min(192,source.height-y)
                val mx=FloatArray(w*h);val my=FloatArray(w*h)
                for(j in 0 until h) for(i in 0 until w) { mx[j*w+i]=coordinate(x+i,y+j,0);my[j*w+i]=coordinate(x+i,y+j,1) }
                val left=floor(mx.minOrNull()!!-4).toInt().coerceIn(0,source.width-1)
                val top=floor(my.minOrNull()!!-4).toInt().coerceIn(0,source.height-1)
                val right=ceil(mx.maxOrNull()!!+5).toInt().coerceIn(left+1,source.width)
                val bottom=ceil(my.maxOrNull()!!+5).toInt().coerceIn(top+1,source.height)
                require((right-left).toLong()*(bottom-top)<=4_000_000) { "تغير كبير في شبكة التسطيح" }
                val patch=Bitmap.createBitmap(source,left,top,right-left,bottom-top)
                val input=Mat();val result=Mat();val mapX=Mat(h,w,CvType.CV_32F);val mapY=Mat(h,w,CvType.CV_32F);var tile: Bitmap?=null
                try {
                    Utils.bitmapToMat(patch,input);if(patch!==source) patch.recycle()
                    for(i in mx.indices) { mx[i]-=left;my[i]-=top };mapX.put(0,0,mx);mapY.put(0,0,my)
                    Imgproc.remap(input,result,mapX,mapY,Imgproc.INTER_CUBIC,Core.BORDER_CONSTANT,Scalar(255.0,255.0,255.0,255.0))
                    tile=createBitmap(w,h);Utils.matToBitmap(result,tile);canvas.drawBitmap(tile,x.toFloat(),y.toFloat(),null)
                } finally { if(patch!==source && !patch.isRecycled) patch.recycle();tile?.recycle();listOf(input,result,mapX,mapY).forEach { it.release() } }
            }
            return DewarpResult(output,(System.nanoTime()-started)/1_000_000,minimum,coverage)
        } catch(error: Throwable) { output.recycle();throw error }
    }
    @Synchronized override fun close() { session?.close();session=null }
    companion object { const val SHA="7376bae030f4c5bd75c456fac44cd99e1d36d8b2fdf0d10f7cb4a626a2417cb4" }
}
