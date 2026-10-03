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
    @Test fun diagnostic300RealPaperProcessing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scannerBenchmark")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val assets=ins.context.assets
        val manifest=JSONObject(assets.open("benchmark/manifest.json").bufferedReader().use { it.readText() })
        val samples=manifest.getJSONArray("samples");assertEquals(300,samples.length())
        val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() };val records=JSONArray()
        var reduced=0;var originals=0;var checkedLines=0;var codesChecked=0;var totalMs=0L
        ScanOcr(context).use { ocr -> try {
            for(i in 0 until samples.length()) {
                val sample=samples.getJSONObject(i);val input=File(context.cacheDir,"processing-benchmark.jpeg")
                assets.open("benchmark/"+sample.getString("file")).use { source -> input.outputStream().use { source.copyTo(it) } }
                val hash=ScanSessionStore.sha256(input);assertEquals(sample.getString("sha256"),hash)
                val info=ScanSourceImage.info(input)
                // This ISOLATED filter benchmark uses publisher boundaries only
                // to obtain the input paper. Detection metrics remain separate;
                // these labels NEVER select or correct predicted crop corners.
                val quad=DocumentQuad((0..3).map { k -> sample.getJSONArray("corners").getJSONArray(k).let {
                    ScanPoint(it.getDouble(0)/(info.width-1),it.getDouble(1)/(info.height-1)) } })
                require(quad.valid())
                val base=ScanSourceImage.perspective(input,quad,maxSide=1400,maxPixels=1_400_000)
                var changed: Bitmap?=null;val started=System.nanoTime()
                val mode=if(i%2==0) ScanFilter.AUTO else ScanFilter.CLEAN_WHITE
                val record=JSONObject().put("file",sample.getString("file")).put("requested_filter",mode.name)
                try {
                    val codes=ScanQualityGuard.barcodes(base);val before=ocr.read(base,maxLines=12)
                    checkedLines+=before.lines.size;codesChecked+=codes.size
                    fun reasons(image: Bitmap): List<String> {
                        val reading=ocr.read(image,before.lines.map { it.box },12)
                        return ScanGuardPolicy.evaluate(before.lines.map { it.text to it.confidence },reading.lines.map { it.text to it.confidence },
                            codes,ScanQualityGuard.barcodes(image)).reasons+ScanQualityGuard.detailReasons(base,image,mode)
                    }
                    changed=ScanPaperProcessor.process(base,mode).bitmap
                    var problems=reasons(changed);record.put("initial_guard_reasons",JSONArray(problems));var strength=1.0
                    if(problems.isNotEmpty()) {
                        changed.recycle();changed=null;strength=.35;reduced++
                        changed=ScanPaperProcessor.process(base,mode,strength).bitmap;problems=reasons(changed)
                        record.put("reduced_guard_reasons",JSONArray(problems))
                    }
                    if(problems.isNotEmpty()) { changed.recycle();changed=base;originals++;record.put("applied_filter","ORIGINAL") }
                    else record.put("applied_filter",mode.name)
                    assertTrue(ScanQualityGuard.barcodes(changed).containsAll(codes))
                    assertTrue(ScanQualityGuard.detailReasons(base,changed,mode).isEmpty())
                    assertEquals(hash,ScanSessionStore.sha256(input))
                    val elapsed=(System.nanoTime()-started)/1_000_000;totalMs+=elapsed
                    record.put("strength",strength).put("ocr_validated_lines",before.lines.size).put("barcode_count",codes.size)
                        .put("validation_and_processing_ms",elapsed).put("source_sha256",hash)
                    if(i%60==0 || (originals<=6 && changed===base)) {
                        for((label,image) in listOf("rectified" to base,"final" to changed))
                            File(folder,"processing-${i.toString().padStart(3,'0')}-$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    }
                } finally { if(changed!==base) changed?.recycle();base.recycle();input.delete() }
                records.put(record);File(folder,"processing-records.json").writeText(records.toString(2))
            }
            File(folder,"processing-summary.json").writeText(JSONObject().put("real_frames",300).put("independent_documents",30)
                .put("reduced_strength",reduced).put("original_fallback",originals).put("ocr_validated_lines",checkedLines)
                .put("readable_codes_checked",codesChecked).put("mean_validation_and_processing_ms",totalMs/300.0)
                .put("scope","Isolated AUTO/CLEAN_WHITE processing at <=1.4 MP on publisher crop; first 12 OCR lines per image plus whole-image detail/QR guard. Safe fallback is counted, not hidden.")
                .put("training_overlap","unknown").toString(2))
            assertEquals(300,records.length())
        } finally { ScannerTestDiagnostics.publish(folder) } }
    }
    @Test fun diagnostic300RealFrames() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("scannerBenchmark")=="true")
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext;val assets=ins.context.assets
        val manifest=JSONObject(assets.open("benchmark/manifest.json").bufferedReader().use { it.readText() })
        val samples=manifest.getJSONArray("samples");assertEquals(300,samples.length())
        val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        try {
        val records=JSONArray();val detector=DocQuadDetector(context)
        var detected=0;var manual=0;var clipped=0;var unsafeAuto=0;var peakMemory=0L
        val errors=ArrayList<Double>();val initialErrors=ArrayList<Double>();val edges=ArrayList<Double>();val times=ArrayList<Double>()
        val logicalErrors=ArrayList<Double>();val confidences=ArrayList<Double>()
        val boundaryErrors=ArrayList<Double>();val boundaryEdges=ArrayList<Double>()
        try {
            for(i in 0 until samples.length()) {
                val sample=samples.getJSONObject(i);val input=File(context.cacheDir,"benchmark-source.jpeg")
                assets.open("benchmark/"+sample.getString("file")).use { source -> input.outputStream().use { source.copyTo(it) } }
                assertEquals(sample.getString("sha256"),ScanSessionStore.sha256(input))
                val info=ScanSourceImage.info(input);val bitmap=ScanSourceImage.preview(input,maxSide=1600)
                val started=System.nanoTime();val detection=try { detector.detect(bitmap) } finally { bitmap.recycle() }
                val logicalGt=(0..3).map { sample.getJSONArray("corners").getJSONArray(it).let { p -> ScanPoint(p.getDouble(0),p.getDouble(1)) } }
                val record=JSONObject().put("file",sample.getString("file")).put("sequence",sample.getString("sequence"))
                    .put("category",sample.getString("category")).put("detected",detection.quad!=null).put("detection_ms",detection.elapsedMs)
                val initial=detection.quad
                if(initial!=null) {
                    detected++;val refinement=FullResolutionEdgeRefiner.refine(input,initial)
                    val pixels=refinement.quad.points.map { ScanPoint(it.x*(info.width-1),it.y*(info.height-1)) }
                    val before=initial.points.map { ScanPoint(it.x*(info.width-1),it.y*(info.height-1)) }
                    val boundary=refinement.boundaryQuad.points.map { ScanPoint(it.x*(info.width-1),it.y*(info.height-1)) }
                    // SmartDoc labels follow printing orientation; inference follows
                    // image orientation. Match CYCLIC start only, keep adjacency and
                    // winding. Use the same correspondence before/after refinement.
                    val cycle=(0..3).minBy { shift -> before.indices.sumOf { k -> before[k].distance(logicalGt[(k+shift)%4]) } }
                    val gt=before.indices.map { logicalGt[(it+cycle)%4] }
                    val logical=logicalGt.zip(pixels).map { (a,b) -> a.distance(b) }.average()
                    logicalErrors.add(logical);confidences.add(initial.confidence)
                    val corner=gt.zip(pixels).map { (a,b) -> a.distance(b) }.average()
                    val old=gt.zip(before).map { (a,b) -> a.distance(b) }.average()
                    val rawCorner=gt.zip(boundary).map { (a,b) -> a.distance(b) }.average()
                    val rawEdge=gt.indices.map { e ->
                        val line=ScanGeometry.lineThrough(boundary[e],boundary[(e+1)%4])!!
                        (0..20).map { n -> val a=gt[e];val b=gt[(e+1)%4];val t=n/20.0
                            line.distance(ScanPoint(a.x*(1-t)+b.x*t,a.y*(1-t)+b.y*t)) }.average() }.average()
                    boundaryErrors.add(rawCorner);boundaryEdges.add(rawEdge)
                    val edge=gt.indices.map { e ->
                        val line=ScanGeometry.lineThrough(pixels[e],pixels[(e+1)%4])!!
                        (0..20).map { n -> val a=gt[e];val b=gt[(e+1)%4];val t=n/20.0
                            line.distance(ScanPoint(a.x*(1-t)+b.x*t,a.y*(1-t)+b.y*t)) }.average() }.average()
                    val inward=gt.maxOf { p -> pixels.indices.maxOf { e ->
                        val a=pixels[e];val b=pixels[(e+1)%4]
                        -((b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x))/a.distance(b) } }
                    if(inward>2) { clipped++;if(!refinement.needsManualReview) unsafeAuto++ };if(refinement.needsManualReview) manual++
                    errors.add(corner);initialErrors.add(old);edges.add(edge)
                    record.put("corner_error_px",corner).put("model_corner_error_px",old).put("edge_error_px",edge)
                        .put("max_inward_px",inward).put("accepted_edges",refinement.acceptedEdges).put("manual_review",refinement.needsManualReview)
                        .put("refinement_ms",refinement.elapsedMs).put("refined_polygon",polygon(pixels)).put("model_polygon",polygon(before))
                        .put("boundary_polygon",polygon(boundary)).put("boundary_corner_error_px",rawCorner).put("boundary_edge_error_px",rawEdge)
                        .put("crop_padding_source_px",refinement.paddingPixels).put("edge_profile_support",JSONArray(refinement.inlierFractions))
                        .put("edge_transition_width_px",JSONArray(refinement.transitionWidthsPixels))
                        .put("ground_truth_polygon",polygon(gt)).put("ground_truth_logical_polygon",polygon(logicalGt))
                        .put("cyclic_gt_start",cycle).put("logical_corner_error_px",logical)
                        .put("model_confidence",initial.confidence).put("corner_confidence",JSONArray(initial.cornerConfidence))
                        .put("corner_peak_probability",JSONArray(detection.cornerPeakProbability)).put("corner_prominence_z",JSONArray(detection.cornerProminenceZ))
                        .put("mask_agreement",detection.maskAgreement).put("edge_residual_px",JSONArray(refinement.residualPixels))
                    if(i%30==0 || corner>18 || inward>8 || (inward>2 && !refinement.needsManualReview)) {
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
        fun stats(v: List<Double>)=JSONObject().put("count",v.size).apply { if(v.isNotEmpty()) put("mean",v.average()).put("p95",v.sorted()[floor((v.size-1)*.95).toInt()]).put("max",v.maxOrNull()) }
        val summary=JSONObject().put("dataset",manifest.getString("dataset")).put("attribution",manifest.getString("attribution"))
            .put("real_frames",300).put("independent_documents",30).put("sequences",150).put("training_overlap","unknown")
            .put("device",android.os.Build.FINGERPRINT).put("api",android.os.Build.VERSION.SDK_INT)
            .put("detected",detected).put("manual_review",manual).put("inward_over_2px",clipped).put("unsafe_auto_crops",unsafeAuto)
            .put("corner_correspondence","cyclic start aligned to initial polygon; adjacency/winding unchanged")
            .put("legacy_logical_corner_px",stats(logicalErrors)).put("model_confidence",stats(confidences))
            .put("model_corner_px",stats(initialErrors)).put("refined_corner_px",stats(errors)).put("refined_edge_px",stats(edges))
            .put("boundary_corner_px",stats(boundaryErrors)).put("boundary_edge_px",stats(boundaryEdges))
            .put("crop_padding_scope","6 original source pixels outward; boundary and final padded crop errors are reported separately")
            .put("diagnostic_total_ms",stats(times)).put("sampled_heap_and_native_bytes",peakMemory)
            .put("memory_scope","phase-boundary samples, not peak PSS").put("timing_scope","detection/refinement and conditional visual output, excludes full pipeline/OCR")
        File(folder,"summary.json").writeText(summary.toString(2))
        assertTrue("Model failed on most documents: "+detected+"/300",detected>=270);assertEquals(300,records.length())
        assertEquals("An inward crop was incorrectly accepted without manual review; inspect saved overlays",0,unsafeAuto)
        } finally { ScannerTestDiagnostics.publish(folder) }
    }
    private fun polygon(points: List<ScanPoint>)=JSONArray().apply { points.forEach { put(JSONArray().put(it.x).put(it.y)) } }
}
