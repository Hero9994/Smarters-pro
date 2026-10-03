package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

data class ScanProcessedPage(val before: Bitmap,val after: Bitmap,val warnings: List<String>,val report: JSONObject): AutoCloseable {
    override fun close() { if(after!==before && !after.isRecycled) after.recycle();if(!before.isRecycled) before.recycle() }
}
/** Reversible native-ROI crop, conditional geometry-only UVDoc, classical LAB
 * cleaning, OCR/QR/detail guard. Original bytes are never modified.
 */
class ScannerEngine(context: Context): AutoCloseable {
    private val ENGINE_CONTEXT=context.applicationContext
    private val detector=DocQuadDetector(context)
    private val ocr=ScanOcr(context)
    private val dewarper=UvDocDewarper(context)
    private var cachedKey=""
    private var cachedReading: ScanOcrReading?=null
    private var cachedCodes=emptySet<String>()
    fun detect(store: ScanSessionStore,page: ScanPage) {
        store.verifySource(page)
        val preview=ScanSourceImage.preview(store.source(page),page.turns,1600)
        val detection=try { detector.detect(preview) } finally { preview.recycle() }
        val initial=detection.quad
        val refined=initial?.let { FullResolutionEdgeRefiner.refine(store.source(page),it,page.turns) }
        page.quad=refined?.quad ?: DocumentQuad.inset()
        page.ready=false;page.review=refined?.needsManualReview ?: true
        val captureReport=page.report.optJSONObject("capture")
        page.report=JSONObject().put("capture",captureReport).put("detection_ms",detection.elapsedMs).put("mask_agreement",detection.maskAgreement)
            .put("detection_warning",detection.warning).put("model_polygon",polygon(initial))
            .put("refined_polygon",polygon(refined?.quad)).put("refinement_ms",refined?.elapsedMs)
            .put("boundary_polygon",polygon(refined?.boundaryQuad)).put("crop_padding_source_px",refined?.paddingPixels)
            .put("accepted_edges",refined?.acceptedEdges ?: 0).put("edge_residual_px",JSONArray(refined?.residualPixels ?: emptyList<Double>()))
            .put("edge_profile_support",JSONArray(refined?.inlierFractions ?: emptyList<Double>()))
            .put("edge_transition_width_px",JSONArray(refined?.transitionWidthsPixels ?: emptyList<Double>()))
        store.save();detector.close()
    }
    fun process(store: ScanSessionStore,page: ScanPage,onStage: (String)->Unit={}): ScanProcessedPage =
        synchronized(PROCESS_LOCK) { processLocked(store,page,onStage) }
    private fun processLocked(store: ScanSessionStore,page: ScanPage,onStage: (String)->Unit): ScanProcessedPage {
        store.verifySource(page);require(page.quad.valid())
        val started=System.nanoTime();val warnings=mutableListOf<String>()
        val key=page.sourceHash+page.hdrHash+page.turns+page.quad.points.toString()+page.dewarp+page.paperRatio
        val report=JSONObject(page.report.toString())
        onStage("التأكد من حدود المحتوى في الصورة الأصلية")
        val rawPreview=ScanSourceImage.preview(store.source(page),page.turns,2200)
        var cropBaseline: ScanOcrReading?=null
        val rawCodes=try {
            val codes=ScanQualityGuard.barcodes(rawPreview)
            // Source-domain validation is separate from the filter guard. A
            // reference row removed by the homography is absent from BOTH
            // rectified/filter images, so a post-filter comparison cannot see it.
            if(page.quad.area()<.999) {
                val model=report.optJSONArray("model_polygon")
                val expected=if(model!=null && model.length()==4) DocumentQuad((0..3).map {
                    val point=model.getJSONArray(it);ScanPoint(point.getDouble(0),point.getDouble(1))
                }).takeIf { it.valid() } ?: page.quad else page.quad
                val paper=expected.copy(points=ScanGeometry.padded(expected.points,rawPreview.width,rawPreview.height,24.0))
                val regions=runCatching { ScanCropContentGuard.atRisk(ocr.detect(rawPreview),page.quad,paper) }
                    .getOrElse { warnings.add("تعذر فحص النص قرب حدود القص؛ راجع الأصل والزوايا");emptyList() }
                if(regions.size>12) {
                    page.ready=false;page.review=true;store.save()
                    error("توجد أسطر كثيرة قرب حدود القص أو خارجه؛ وسّع حدود الورقة وراجع الزوايا")
                }
                if(regions.isNotEmpty()) cropBaseline=runCatching { ocr.read(rawPreview,regions,12) }
                    .getOrElse { warnings.add("تعذر فحص الحروف قرب حدود القص؛ راجع الأصل والزوايا");null }
                report.put("source_boundary_regions",regions.size)
            }
            codes
        } finally { rawPreview.recycle() }
        var base: Bitmap?=null;var changed: Bitmap?=null;var originalBase: Bitmap?=null
        try {
            onStage("تصحيح شكل الورقة")
            val warpStart=System.nanoTime()
            val analysis=ScanSourceImage.perspective(store.source(page),page.quad,page.turns,1100,1_300_000,paperRatio=page.paperRatio)
            val geometry=try { ScanPageGeometry.analyze(analysis) } finally { analysis.recycle() }
            base=ScanSourceImage.perspective(store.source(page),page.quad,page.turns,deskewDegrees=geometry.deskewDegrees,paperRatio=page.paperRatio)
            report.put("perspective_ms",elapsed(warpStart)).put("deskew_degrees",geometry.deskewDegrees)
                .put("curved",geometry.curved).put("curve_confidence",geometry.curveConfidence).put("paper_ratio",page.paperRatio)
                .put("curved_lines",geometry.curvedLines).put("dewarp_applied",false)
            onStage("فحص النص والرموز قبل التنظيف")
            val warpedCodes=ScanQualityGuard.barcodes(base)
            if((rawCodes-warpedCodes).isNotEmpty()) {
                page.ready=false;page.review=true;store.save()
                error("القص أضعف قراءة رمز كان واضحًا في الأصل؛ وسّع حدود الورقة أو أعد التصوير")
            }
            var baseline=if(cachedKey==key) cachedReading else null
            var codes=if(cachedKey==key) cachedCodes else warpedCodes
            if(baseline==null) baseline=runCatching { ocr.read(base,maxLines=40) }.getOrElse {
                warnings.add("تعذرت مقارنة النص؛ حافظنا على تنظيف خفيف وفحص التفاصيل");ScanOcrReading(emptyList(),0,false) }
            val warpedReading=baseline
            cropBaseline?.let { sourceReading ->
                val protectedLines=sourceReading.lines.filter { it.confidence>=.88 }
                val decision=ScanGuardPolicy.evaluate(protectedLines.map { it.text to it.confidence },
                    warpedReading.lines.map { it.text to it.confidence },emptySet(),emptySet())
                report.put("source_boundary_ocr_ms",sourceReading.elapsedMs).put("source_boundary_validated_lines",protectedLines.size)
                if(!decision.accepted) {
                    page.ready=false;page.review=true;store.save()
                    error("القص فقد أو غيّر قراءة نص واضح قرب الحافة؛ وسّع حدود الورقة أو أعد التصوير")
                }
                if(protectedLines.size<sourceReading.lines.size || protectedLines.isEmpty())
                    warnings.add("بعض النص قرب الحافة غير واضح؛ راجع القص في الصورة الأصلية")
            }
            val originalReading=baseline
            report.put("hdr_applied",false)
            if(page.hdrSource!=null && page.filter!=ScanFilter.ORIGINAL) {
                onStage("محاذاة HDR وفحص الحركة والنص والرموز")
                val hdrStarted=System.nanoTime()
                try {
                    val free=Runtime.getRuntime().let { it.maxMemory()-(it.totalMemory()-it.freeMemory()) }
                    require(free>=64_000_000L+base.width.toLong()*base.height*8) { "الذاكرة لا تكفي لفحص HDR؛ بقي الأصل" }
                    store.verifyHdr(page)
                    val aligned=ScanHdrFusion.align(store.source(page),checkNotNull(store.hdr(page)),page.quad,page.turns,
                        base,geometry.deskewDegrees,page.paperRatio)
                    changed=aligned.image
                    val ghosts=ScanHdrFusion.ghosting(base,changed)
                    val reading=ocr.read(changed,maxLines=40)
                    val afterCodes=ScanQualityGuard.barcodes(changed)
                    val guard=ScanGuardPolicy.evaluate(baseline.lines.map { it.text to it.confidence },
                        reading.lines.map { it.text to it.confidence },codes,afterCodes)
                    val beforeQuality=ScanQuality.analyze(base,DocumentQuad.inset(.02))
                    val afterQuality=ScanQuality.analyze(changed,DocumentQuad.inset(.02))
                    val improved=afterQuality.shadowSeverity<beforeQuality.shadowSeverity*.85 ||
                        afterQuality.clippedFraction+.003<beforeQuality.clippedFraction ||
                        afterQuality.darkFraction+.015<beforeQuality.darkFraction
                    val measurable=baseline.lines.count { it.confidence>=.88 }>=3 || codes.isNotEmpty()
                    val details=ScanQualityGuard.detailReasons(base,changed,ScanFilter.PHOTO)
                    val accepted=ghosts.acceptable() && guard.accepted && details.isEmpty() && measurable &&
                        improved && afterQuality.sharpness>=beforeQuality.sharpness*.80
                    report.put("hdr_matches",aligned.matches).put("hdr_inlier_fraction",aligned.inlierFraction)
                        .put("hdr_lost_ink_fraction",ghosts.lostInk).put("hdr_new_ink_fraction",ghosts.newInk)
                        .put("hdr_quality_improved",improved).put("hdr_guard_reasons",JSONArray(guard.reasons+details))
                    if(accepted) {
                        originalBase=base;base=changed;changed=null;baseline=reading;codes=afterCodes
                        report.put("hdr_applied",true)
                    } else {
                        changed.recycle();changed=null
                        warnings.add("أبقينا اللقطة العادية لأن HDR لم يثبت تحسنًا يحافظ على التفاصيل")
                    }
                } catch(error: Exception) {
                    changed?.recycle();changed=null
                    report.put("hdr_fallback",error.localizedMessage ?: error.javaClass.simpleName)
                    warnings.add("أبقينا اللقطة العادية لأن محاذاة HDR أو فحصه لم ينجح")
                } catch(_: OutOfMemoryError) {
                    changed?.recycle();changed=null;report.put("hdr_fallback","memory pressure")
                    warnings.add("أبقينا اللقطة العادية لأن الذاكرة مشغولة")
                } finally { report.put("hdr_validation_ms",elapsed(hdrStarted)) }
            }
            if(page.dewarp && geometry.curved && geometry.curveConfidence>=.6 && cachedKey!=key) {
                onStage("تسطيح الانحناء وفحص النتيجة")
                try {
                    val uv=dewarper.dewarp(base);changed=uv.image
                    val reading=ocr.read(changed,maxLines=40);val afterCodes=ScanQualityGuard.barcodes(changed)
                    val guard=ScanGuardPolicy.evaluate(baseline.lines.map { it.text to it.confidence },reading.lines.map { it.text to it.confidence },codes,afterCodes)
                    val newGeometry=ScanPageGeometry.analyze(changed)
                    val measurable=baseline.lines.count { it.confidence>=.88 }>=3
                    if(guard.accepted && measurable && newGeometry.bendPixels<geometry.bendPixels*.85) {
                        base.recycle();base=changed;changed=null;baseline=reading;codes=afterCodes
                        report.put("dewarp_applied",true).put("dewarp_ms",uv.elapsedMs).put("dewarp_min_jacobian",uv.gridMinimumJacobian)
                    } else { changed.recycle();changed=null;warnings.add("أبقينا التسطيح العادي لأن التسطيح المتقدم لم يثبت تحسنًا آمنًا") }
                } catch(error: Exception) { changed?.recycle();changed=null;warnings.add(error.localizedMessage ?: "لم ينجح التسطيح المتقدم؛ بقي القص العادي") }
                finally { dewarper.close() }
            }
            if(report.optBoolean("dewarp_applied")) { cachedKey="";cachedReading=null }
            else { cachedKey=key;cachedReading=originalReading;cachedCodes=warpedCodes }
            val stableBase=base
            onStage("إزالة اختلاف الإضاءة وتنظيف الورق")
            var applied=page.filter
            var strength=if(baseline.lines.isEmpty() && !baseline.complete) .4 else 1.0
            val paper=ScanPaperProcessor.process(stableBase,applied,strength);changed=paper.bitmap
            report.put("illumination_ms",paper.elapsedMs).put("shadow_severity",paper.shadowSeverity).put("paper_fraction",paper.paperFraction)
            onStage("التأكد من بقاء الحروف والأرقام والرموز")
            val guardStart=System.nanoTime()
            fun validate(image: Bitmap,mode: ScanFilter): Pair<List<String>,ScanOcrReading> {
                if(image===stableBase) return emptyList<String>() to baseline
                val reading=runCatching { ocr.read(image,baseline.lines.map { it.box },40) }.getOrElse {
                    return listOf("تعذر فحص النص بعد المعالجة") to baseline }
                val decision=ScanGuardPolicy.evaluate(baseline.lines.map { it.text to it.confidence },reading.lines.map { it.text to it.confidence },codes,ScanQualityGuard.barcodes(image))
                return (decision.reasons+ScanQualityGuard.detailReasons(stableBase,image,mode)).distinct() to reading
            }
            var validation=validate(checkNotNull(changed),applied)
            if(validation.first.isNotEmpty() && changed!==stableBase) {
                changed?.recycle();changed=null;strength=.35
                changed=ScanPaperProcessor.process(stableBase,if(applied==ScanFilter.BLACK_WHITE) ScanFilter.AUTO else applied,strength).bitmap
                if(applied==ScanFilter.BLACK_WHITE) applied=ScanFilter.AUTO
                validation=validate(checkNotNull(changed),applied);warnings.add("خففنا التنظيف تلقائيًا لحماية التفاصيل")
            }
            if(validation.first.isNotEmpty()) {
                if(changed!==stableBase) changed?.recycle();changed=stableBase;applied=ScanFilter.ORIGINAL
                warnings.addAll(validation.first);warnings.add("حفظنا الورقة المصححة بلا فلتر لحماية المحتوى");validation=emptyList<String>() to baseline
            }
            report.put("raw_preview_barcodes",JSONArray(rawCodes.toList())).put("guard_ms",elapsed(guardStart)).put("ocr_before_ms",baseline.elapsedMs)
                .put("ocr_after_ms",validation.second.elapsedMs).put("ocr_complete",baseline.complete)
                .put("ocr_validated_lines",baseline.lines.size).put("ocr_text",validation.second.text)
                .put("barcodes",JSONArray(codes.toList())).put("requested_filter",page.filter.name)
                .put("applied_filter",applied.name).put("strength",strength).put("warnings",JSONArray(warnings))
                .put("output_width",stableBase.width).put("output_height",stableBase.height).put("total_ms",elapsed(started))
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val latest=ScanSessionStore.open(ENGINE_CONTEXT,store.id).pages.first { it.id==page.id }
            require(latest.quad.points==page.quad.points && latest.turns==page.turns && latest.filter==page.filter && latest.dewarp==page.dewarp && latest.paperRatio==page.paperRatio && latest.hdrHash==page.hdrHash) { "تغيرت إعدادات الصفحة؛ أعد تطبيق القص" }
            val comparison=originalBase ?: stableBase
            store.saveBitmap(comparison,store.rectified(page));store.saveBitmap(checkNotNull(changed),store.processed(page))
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            page.report=report;page.ready=true;page.review=false;store.save()
            val output=checkNotNull(changed)
            if(stableBase!==comparison && stableBase!==output) stableBase.recycle()
            return ScanProcessedPage(comparison,output,warnings.distinct(),report).also { base=null;changed=null;originalBase=null }
        } finally {
            if(changed!==base && changed!==originalBase) changed?.recycle()
            if(base!==originalBase) base?.recycle();originalBase?.recycle()
        }
    }
    override fun close() { detector.close();ocr.close();dewarper.close();cachedReading=null }
    companion object {
        private val PROCESS_LOCK=Any()
        private fun elapsed(start: Long)=(System.nanoTime()-start)/1_000_000
        private fun polygon(q: DocumentQuad?)=JSONArray().apply { q?.points?.forEach { put(JSONArray().put(it.x).put(it.y)) } }
    }
}
