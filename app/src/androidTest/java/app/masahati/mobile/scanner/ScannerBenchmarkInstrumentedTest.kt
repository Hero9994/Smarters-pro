package app.masahati.mobile.scanner

import android.graphics.*
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.*

/** Diagnostic benchmark, not an acceptance declaration. Stores every failure.
 * Training overlap is unknown. Timings are emulator diagnostics, not phone speed.
 */
@RunWith(AndroidJUnit4::class)
class ScannerBenchmarkInstrumentedTest {
    @Test fun diagnostic300RealFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scannerBenchmark")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val assets=ins.context.assets
        val manifest=JSONObject(assets.open("benchmark/manifest.json").bufferedReader().use { it.readText() })
        val samples=manifest.getJSONArray("samples");assertEquals(300,samples.length())
        val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        val records=JSONArray();val detector=DocQuadDetector(context)
        var detected=0;var manual=0;var clipped=0;var peakMemory=0L
        val errors=ArrayList<Double>();val initialErrors=ArrayList<Double>();val edges=ArrayList<Double>();val times=ArrayList<Double>()
        try {
            for(i in 0 until samples.length()) {
                val sample=samples.getJSONObject(i);val input=File(context.cacheDir,"benchmark-source.jpeg")
                assets.open("benchmark/"+sample.getString("file")).use { source -> input.outputStream().use { source.copyTo(it) } }
                assertEquals(sample.getString("sha256"),ScanSessionStore.sha256(input))
                val info=ScanSourceImage.info(input);val bitmap=ScanSourceImage.preview(input,maxSide=1600)
                val started=System.nanoTime();val detection=try { detector.detect(bitmap) } finally { bitmap.recycle() }
                val gt=(0..3).map { sample.getJSONArray("corners").getJSONArray(it).let { p -> ScanPoint(p.getDouble(0),p.getDouble(1)) } }
                val record=JSONObject().put("file",sample.getString("file")).put("sequence",sample.getString("sequence"))
                    .put("category",sample.getString("category")).put("detected",detection.quad!=null).put("detection_ms",detection.elapsedMs)
                val initial=detection.quad
                if(initial!=null) {
                    detected++;val refinement=FullResolutionEdgeRefiner.refine(input,initial)
                    val pixels=refinement.quad.points.map { ScanPoint(it.x*info.width,it.y*info.height) }
                    val before=initial.points.map { ScanPoint(it.x*info.width,it.y*info.height) }
                    val corner=gt.zip(pixels).map { (a,b) -> a.distance(b) }.average()
                    val old=gt.zip(before).map { (a,b) -> a.distance(b) }.average()
                    val edge=gt.indices.map { e ->
                        val line=ScanGeometry.lineThrough(pixels[e],pixels[(e+1)%4])!!
                        (0..20).map { n -> val a=gt[e];val b=gt[(e+1)%4];val t=n/20.0
                            line.distance(ScanPoint(a.x*(1-t)+b.x*t,a.y*(1-t)+b.y*t)) }.average() }.average()
                    val inward=gt.maxOf { p -> pixels.indices.maxOf { e ->
                        val a=pixels[e];val b=pixels[(e+1)%4]
                        -((b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x))/a.distance(b) } }
                    if(inward>2) clipped++;if(refinement.needsManualReview) manual++
                    errors.add(corner);initialErrors.add(old);edges.add(edge)
                    record.put("corner_error_px",corner).put("model_corner_error_px",old).put("edge_error_px",edge)
                        .put("max_inward_px",inward).put("accepted_edges",refinement.acceptedEdges).put("manual_review",refinement.needsManualReview)
                        .put("refinement_ms",refinement.elapsedMs).put("refined_polygon",polygon(pixels)).put("model_polygon",polygon(before))
                    if(i%30==0 || corner>18 || inward>8) {
                        val image=ScanSourceImage.preview(input,maxSide=1200)
                        try {
                            val canvas=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=3f }
                            fun draw(points: List<ScanPoint>,color: Int) { paint.color=color;val path=Path()
                                points.forEachIndexed { index,p -> val x=(p.x/info.width*image.width).toFloat();val y=(p.y/info.height*image.height).toFloat()
                                    if(index==0) path.moveTo(x,y) else path.lineTo(x,y) };path.close();canvas.drawPath(path,paint) }
                            draw(gt,Color.RED);draw(before,Color.YELLOW);draw(pixels,Color.GREEN)
                            File(folder,i.toString().padStart(3,'0')+"-overlay.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                        } finally { image.recycle() }
                    }
                    if(i%30==0) {
                        val warped=ScanSourceImage.perspective(input,refinement.quad,maxSide=1600,maxPixels=2_000_000)
                        try { File(folder,i.toString().padStart(3,'0')+"-perspective.png").outputStream().use { warped.compress(Bitmap.CompressFormat.PNG,100,it) } }
                        finally { warped.recycle() }
                    }
                } else manual++
                val millis=(System.nanoTime()-started)/1_000_000.0;times.add(millis);record.put("diagnostic_total_ms",millis)
                peakMemory=max(peakMemory,Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() }+Debug.getNativeHeapAllocatedSize())
                records.put(record);File(folder,"records.json").writeText(records.toString(2));input.delete()
            }
        } finally { detector.close() }
        fun stats(v: List<Double>)=JSONObject().put("mean",v.average()).put("p95",v.sorted()[floor((v.size-1)*.95).toInt()]).put("max",v.maxOrNull())
        val summary=JSONObject().put("dataset",manifest.getString("dataset")).put("attribution",manifest.getString("attribution"))
            .put("real_frames",300).put("independent_documents",30).put("sequences",150).put("training_overlap","unknown")
            .put("device",android.os.Build.FINGERPRINT).put("api",android.os.Build.VERSION.SDK_INT)
            .put("detected",detected).put("manual_review",manual).put("inward_over_2px",clipped)
            .put("model_corner_px",stats(initialErrors)).put("refined_corner_px",stats(errors)).put("refined_edge_px",stats(edges))
            .put("diagnostic_total_ms",stats(times)).put("sampled_heap_and_native_bytes",peakMemory)
            .put("memory_scope","phase-boundary samples, not peak PSS").put("timing_scope","detection/refinement and conditional visual output, excludes full pipeline/OCR")
        File(folder,"summary.json").writeText(summary.toString(2))
        assertTrue("Model failed on most documents: "+detected+"/300",detected>=270);assertEquals(300,records.length())
    }
    private fun polygon(points: List<ScanPoint>)=JSONArray().apply { points.forEach { put(JSONArray().put(it.x).put(it.y)) } }
}
