package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.*
import androidx.core.graphics.createBitmap
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.*

data class DocumentDetection(val quad: DocumentQuad?,val mask: FloatArray,val maskAgreement: Double,
    val elapsedMs: Long,val warning: String?)

/** New adapter for MakeACopy's Apache-2.0 DocQuadNet-256 contract, pinned to
 * 01bebd394b9dd6f3a692f28aea7c0638085eb4da. Black letterbox RGB [0,1] NCHW input;
 * mask_logits and four 64x64 heatmaps. NOT a replacement for native edge refinement.
 * Full upstream attribution and model-license notice are packaged in scanner/licenses.
 */
class DocQuadDetector(context: Context): AutoCloseable {
    private val context=context.applicationContext
    private val environment=OrtEnvironment.getEnvironment()
    private var session: OrtSession?=null
    private val input=FloatArray(3*256*256)
    @Synchronized fun detect(bitmap: Bitmap): DocumentDetection {
        val started=System.nanoTime()
        val scale=min(256.0/bitmap.width,256.0/bitmap.height)
        val w=bitmap.width*scale;val h=bitmap.height*scale;val dx=(256-w)/2;val dy=(256-h)/2
        val letterbox=createBitmap(256,256)
        try {
            val canvas=Canvas(letterbox);canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap,null,RectF(dx.toFloat(),dy.toFloat(),(dx+w).toFloat(),(dy+h).toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
            val pixels=IntArray(256*256);letterbox.getPixels(pixels,0,256,0,0,256,256)
            pixels.forEachIndexed { i,p -> input[i]=Color.red(p)/255f;input[i+65536]=Color.green(p)/255f;input[i+131072]=Color.blue(p)/255f }
            val runner=session ?: OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                environment.createSession(ScanModelAssets.extract(context,"docquad.ort",DOCQUAD_SHA).absolutePath,options)
            }.also { session=it }
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,256,256)).use { tensor ->
                runner.run(mapOf("input" to tensor)).use { result ->
                    val heats=floats(result.get("corner_heatmaps").get() as OnnxTensor)
                    val logits=floats(result.get("mask_logits").get() as OnnxTensor)
                    require(heats.size==16384 && logits.size==4096)
                    val points=ArrayList<ScanPoint>();val confidence=ArrayList<Double>()
                    for(corner in 0..3) {
                        val base=corner*4096;var peak=0;var best=Float.NEGATIVE_INFINITY
                        var sum=0.0;var squares=0.0
                        for(i in 0 until 4096) { val value=heats[base+i];sum+=value;squares+=value*value
                            if(value>best) { best=value;peak=i } }
                        val px=peak%64;val py=peak/64;var sx=0.0;var sy=0.0;var total=0.0
                        for(y in max(0,py-1)..min(63,py+1)) for(x in max(0,px-1)..min(63,px+1)) {
                            val weight=exp((heats[base+y*64+x]-best).toDouble());sx+=(x+.5)*weight;sy+=(y+.5)*weight;total+=weight }
                        points.add(ScanPoint(((sx/total*4-dx)/w).coerceIn(0.0,1.0),((sy/total*4-dy)/h).coerceIn(0.0,1.0)))
                        val std=sqrt(max(1e-8,squares/4096-(sum/4096).pow(2)))
                        confidence.add(sigmoid(best.toDouble())*((best-sum/4096)/std/6).coerceIn(0.0,1.0))
                    }
                    val mask=FloatArray(4096) { sigmoid(logits[it].toDouble()).toFloat() }
                    if(!ScanGeometry.valid(points)) return DocumentDetection(null,mask,0.0,elapsed(started),"عدّل الزوايا يدويًا")
                    val modelPoints=points.map { ScanPoint((it.x*w+dx)/256,(it.y*h+dy)/256) }
                    var intersection=0;var union=0
                    for(y in 0..63) for(x in 0..63) {
                        val polygon=inside(modelPoints,(x+.5)/64,(y+.5)/64);val paper=mask[y*64+x]>.5f
                        if(polygon || paper) union++;if(polygon && paper) intersection++ }
                    val agreement=if(union==0) 0.0 else intersection.toDouble()/union
                    val overall=confidence.min()*agreement
                    return DocumentDetection(DocumentQuad(points,overall,confidence,"DocQuadNet-256"),mask,agreement,elapsed(started),
                        if(overall<.72) "الكشف يحتاج مراجعة الزوايا" else null)
                }
            }
        } catch(error: Exception) {
            return DocumentDetection(null,FloatArray(0),0.0,elapsed(started),"تعذر الكشف التلقائي؛ استخدم الزوايا اليدوية")
        } finally { letterbox.recycle() }
    }
    @Synchronized override fun close() { session?.close();session=null }
    companion object {
        const val DOCQUAD_SHA="f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a"
        fun floats(tensor: OnnxTensor): FloatArray { val buffer: FloatBuffer=tensor.floatBuffer;return FloatArray(buffer.remaining()).also { buffer.get(it) } }
        private fun sigmoid(v: Double)=1/(1+exp(-v.coerceIn(-40.0,40.0)))
        private fun elapsed(t: Long)=(System.nanoTime()-t)/1_000_000
        private fun inside(p: List<ScanPoint>,x: Double,y: Double)=p.indices.all {
            val a=p[it];val b=p[(it+1)%4];(b.x-a.x)*(y-a.y)-(b.y-a.y)*(x-a.x)>=0 }
    }
}
object ScanModelAssets {
    @Synchronized fun extract(context: Context,name: String,hash: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+")))
        val root=File(context.noBackupFilesDir,"scanner-models").apply { mkdirs() }
        val target=File(root,"${hash.take(16)}-$name")
        if(target.isFile && ScanSessionStore.sha256(target)==hash) return target
        val partial=File(root,"$name.partial")
        try {
            context.assets.open("scanner/models/$name").use { source -> partial.outputStream().use { source.copyTo(it) } }
            check(ScanSessionStore.sha256(partial)==hash) { "فشل التحقق من نموذج السكانر" }
            check(partial.renameTo(target));return target
        } finally { partial.delete() }
    }
}
