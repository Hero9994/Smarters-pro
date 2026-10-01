package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.*
import androidx.core.graphics.createBitmap
import ai.onnxruntime.*
import app.masahati.mobile.OpenCvDocumentRectifier
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.*

data class ScanTextBox(val left: Double,val top: Double,val right: Double,val bottom: Double)
data class ScanTextLine(val box: ScanTextBox,val text: String,val confidence: Double)
data class ScanOcrReading(val lines: List<ScanTextLine>,val elapsedMs: Long,val complete: Boolean) {
    val text get()=lines.joinToString("\n") { it.text }
}

/** Apache-2.0 PP-OCRv5 mobile inference using MakeACopy's pinned ORT conversions.
 * Detector BGR ImageNet normalization; recognizers RGB [-1,1], 48px height,
 * CTC blank=0 plus the exact published per-model vocabulary and trailing space.
 * OCR is a validator/metadata extractor; it never writes pixels or invents text.
 */
class ScanOcr(context: Context): AutoCloseable {
    private val context=context.applicationContext
    private val environment=OrtEnvironment.getEnvironment()
    private val sessions=mutableMapOf<String,OrtSession>()
    private val dictionaries=mutableMapOf<String,List<String>>()
    private fun session(name: String): OrtSession = sessions.getOrPut(name) {
        OrtSession.SessionOptions().use { options -> options.setIntraOpNumThreads(2);options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(ScanModelAssets.extract(context,"paddle-$name.ort",HASHES.getValue(name)).absolutePath,options) }
    }
    private fun vocabulary(name: String)=dictionaries.getOrPut(name) {
        val file=ScanModelAssets.extract(context,"${name}_dict.txt",HASHES.getValue("$name-dict"))
        listOf("")+file.readLines(Charsets.UTF_8).map { it.removeSuffix("\r") }.filter { it.isNotEmpty() }
    }
    @Synchronized fun read(source: Bitmap,boxes: List<ScanTextBox>?=null,maxLines: Int=56): ScanOcrReading {
        val started=System.nanoTime();val regions=boxes ?: detect(source)
        val selected=regions.sortedWith(compareBy<ScanTextBox> { it.top }.thenBy { it.left }).take(maxLines)
        val lines=ArrayList<ScanTextLine>()
        for(box in selected) {
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val left=(box.left*source.width).toInt().coerceIn(0,source.width-1);val top=(box.top*source.height).toInt().coerceIn(0,source.height-1)
            val right=ceil(box.right*source.width).toInt().coerceIn(left+1,source.width);val bottom=ceil(box.bottom*source.height).toInt().coerceIn(top+1,source.height)
            val crop=Bitmap.createBitmap(source,left,top,right-left,bottom-top)
            try {
                var result=recognize(crop,"latin")
                if(result.second<.77) {
                    val arabic=recognize(crop,"arabic")
                    if(arabic.first.count { it in '\u0600'..'\u06ff' }>=2 && arabic.second>result.second-.02) result=arabic
                }
                if(result.first.isNotBlank()) lines.add(ScanTextLine(box,result.first,result.second))
            } finally { if(crop!==source) crop.recycle() }
        }
        return ScanOcrReading(lines,(System.nanoTime()-started)/1_000_000,regions.size<=maxLines)
    }
    @Synchronized fun detect(source: Bitmap): List<ScanTextBox> {
        check(OpenCvDocumentRectifier.isAvailable())
        val scale=min(1.0,1280.0/max(source.width,source.height))
        val rw=max(32,(source.width*scale).roundToInt());val rh=max(32,(source.height*scale).roundToInt())
        val w=ceil(rw/32.0).toInt()*32;val h=ceil(rh/32.0).toInt()*32
        val target=createBitmap(w,h);val canvas=Canvas(target);canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source,null,RectF(0f,0f,rw.toFloat(),rh.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
        try {
            val pixels=IntArray(w*h);target.getPixels(pixels,0,w,0,0,w,h);val input=FloatArray(w*h*3);val plane=w*h
            pixels.forEachIndexed { i,p -> input[i]=(Color.blue(p)-.406f*255)/(.225f*255)
                input[i+plane]=(Color.green(p)-.456f*255)/(.224f*255);input[i+2*plane]=(Color.red(p)-.485f*255)/(.229f*255) }
            val runner=session("det")
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,h.toLong(),w.toLong())).use { tensor ->
                runner.run(mapOf(runner.inputNames.first() to tensor)).use { results ->
                    val output=results[0] as OnnxTensor;val shape=output.info.shape;val oh=shape[shape.size-2].toInt();val ow=shape.last().toInt()
                    val probabilities=DocQuadDetector.floats(output)
                    val binary=Mat(oh,ow,CvType.CV_8U);val hierarchy=Mat();val contours=ArrayList<MatOfPoint>()
                    try {
                        binary.put(0,0,ByteArray(ow*oh) { i -> if(probabilities[i]>.3f) 255.toByte() else 0 })
                        Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
                        return contours.mapNotNull { contour ->
                            val rect=Imgproc.boundingRect(contour)
                            if(rect.width<8 || rect.height<3 || rect.area()<24) return@mapNotNull null
                            var sum=0.0;var count=0
                            for(y in rect.y until rect.y+rect.height) for(x in rect.x until rect.x+rect.width) {
                                if(probabilities[y*ow+x]>.3f) { sum+=probabilities[y*ow+x];count++ } }
                            if(count<5 || sum/count<.60) return@mapNotNull null
                            val padY=max(2.0,rect.height*.32);val padX=max(2.0,rect.height*.25)
                            val left=((rect.x-padX)*w/ow/rw).coerceIn(0.0,1.0);val top=((rect.y-padY)*h/oh/rh).coerceIn(0.0,1.0)
                            val right=((rect.x+rect.width+padX)*w/ow/rw).coerceIn(0.0,1.0);val bottom=((rect.y+rect.height+padY)*h/oh/rh).coerceIn(0.0,1.0)
                            if(right<=left || bottom<=top) null else ScanTextBox(left,top,right,bottom)
                        }.sortedBy { it.top }
                    } finally { contours.forEach { it.release() };binary.release();hierarchy.release() }
                }
            }
        } finally { target.recycle() }
    }
    private fun recognize(source: Bitmap,language: String): Pair<String,Double> {
        val width=(ceil((source.width*48.0/source.height).coerceIn(32.0,2048.0)/16).toInt()*16).coerceAtMost(2048)
        val target=createBitmap(width,48)
        try {
            Canvas(target).drawBitmap(source,null,RectF(0f,0f,width.toFloat(),48f),Paint(Paint.FILTER_BITMAP_FLAG))
            val n=width*48;val pixels=IntArray(n);target.getPixels(pixels,0,width,0,0,width,48);val input=FloatArray(3*n)
            pixels.forEachIndexed { i,p -> input[i]=Color.red(p)/127.5f-1;input[i+n]=Color.green(p)/127.5f-1;input[i+2*n]=Color.blue(p)/127.5f-1 }
            val runner=session(language)
            OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),longArrayOf(1,3,48,width.toLong())).use { tensor ->
                runner.run(mapOf(runner.inputNames.first() to tensor)).use { result -> val out=result[0] as OnnxTensor
                    val shape=out.info.shape;return ScanCtc.decode(DocQuadDetector.floats(out),shape[shape.size-2].toInt(),shape.last().toInt(),vocabulary(language)) }
            }
        } finally { target.recycle() }
    }
    @Synchronized override fun close() { sessions.values.forEach { it.close() };sessions.clear() }
    companion object {
        val HASHES=mapOf("det" to "bfb226a460dee7e50b210e20e7c51becff55798150aea45cb9d047c81bfb9c9a",
            "latin" to "5bb93e0fef6fcde14ddadfec23ff9efbc331531ba1ae54baba85605d7794efda",
            "arabic" to "17d31ec78b3dd2168c97595031fdf7adeba145c4cfa5f33278f04e8363fdea9d",
            "latin-dict" to "b95923300a0656f8169feee90143cbfcdb62d82a37b54e6b12c224c3e584916f",
            "arabic-dict" to "2a215ea5877f01b1f8c8803783cda73707222c39a84d4a6cfee9ef502c48248e")
    }
}

object ScanCtc {
    fun decode(values: FloatArray,time: Int,vocabSize: Int,vocabulary: List<String>): Pair<String,Double> {
        require(vocabulary.size==vocabSize && values.size==time*vocabSize)
        val text=StringBuilder();var last=-1;var count=0;var sum=0.0
        for(t in 0 until time) {
            val offset=t*vocabSize;var index=0;var best=values[offset]
            for(i in 1 until vocabSize) if(values[offset+i]>best) { best=values[offset+i];index=i }
            if(index!=0 && index!=last) {
                var rowSum=0.0;var looksLikeProb=true
                for(i in 0 until vocabSize) { val v=values[offset+i];rowSum+=v;if(v<0 || v>1) looksLikeProb=false }
                val confidence=if(looksLikeProb && abs(rowSum-1)<.02) best.toDouble() else {
                    var expSum=0.0;for(i in 0 until vocabSize) expSum+=exp((values[offset+i]-best).toDouble());1/expSum }
                text.append(vocabulary[index]);sum+=confidence;count++
            }
            last=index
        }
        return text.toString().trim() to if(count==0) 0.0 else sum/count
    }
}
