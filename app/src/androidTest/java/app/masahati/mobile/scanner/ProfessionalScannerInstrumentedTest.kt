package app.masahati.mobile.scanner

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.Debug
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.masahati.mobile.AlphaExporter
import app.masahati.mobile.AlphaImporter
import app.masahati.mobile.MasahatiDatabase
import app.masahati.mobile.OpenCvDocumentRectifier
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONObject
import org.json.JSONArray
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class ProfessionalScannerInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun sheet(): Bitmap {
        val b=Bitmap.createBitmap(1100,1550,Bitmap.Config.ARGB_8888);val c=Canvas(b)
        c.drawColor(Color.rgb(202,202,197));val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(175,175,170) }
        c.drawRect(0f,0f,440f,1550f,p);p.color=Color.BLACK;p.textSize=34f
        c.drawText("OFFICIAL DOCUMENT 73071446",80f,160f,p)
        c.drawText("Reference 16010195K535",80f,225f,p)
        c.drawText("Payment 15.07.2026 24.90 EUR",80f,290f,p)
        p.textSize=18f;c.drawText("Small punctuation: i j 1,234.56;",80f,500f,p)
        p.color=Color.rgb(180,20,20);p.style=Paint.Style.STROKE;p.strokeWidth=6f;c.drawCircle(840f,1050f,90f,p)
        p.color=Color.rgb(20,40,180);p.strokeWidth=3f;c.drawLine(150f,1200f,420f,1250f,p)
        p.style=Paint.Style.FILL;p.color=Color.BLACK;c.drawCircle(150f,600f,2f,p)
        val qr=QRCodeWriter().encode("masahati-preserve-73071446",BarcodeFormat.QR_CODE,250,250)
        for(y in 0 until 250) for(x in 0 until 250) b.setPixel(600+x,650+y,if(qr[x,y]) Color.BLACK else Color.WHITE)
        return b
    }
    private fun import(store: ScanSessionStore,bitmap: Bitmap): ScanPage {
        val bytes=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)
        return store.import(ByteArrayInputStream(bytes.toByteArray())).apply {
            quad=DocumentQuad.inset(0.0).copy(confidence=1.0,origin="manual");review=false;store.save()
        }
    }
    @Test fun whitePaperPreservesQrColorAndTinyPunctuation() {
        val source=sheet()
        val canvas=Canvas(source);val bold=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.BLACK;textSize=42f;typeface=Typeface.DEFAULT_BOLD
        }
        repeat(5) { line -> canvas.drawText("OFFICIAL TEXT 73071446",80f,850f+line*52f,bold) }
        // Tonal image without any colored pixels: do not solve text halos by
        // deleting photo protection or relying only on chroma.
        for(y in 330 until 590) for(x in 650 until 980) {
            val value=(128+60*kotlin.math.sin((x-650)*.18)+35*kotlin.math.cos((y-330)*.13)).toInt().coerceIn(0,255)
            source.setPixel(x,y,Color.rgb(value,value,value))
        }
        fun tonalSpread(image: Bitmap): Double {
            var n=0;var sum=0.0;var squares=0.0
            for(y in 360 until 560 step 3) for(x in 680 until 950 step 3) {
                val v=Color.red(image.getPixel(x,y)).toDouble();n++;sum+=v;squares+=v*v
            }
            return kotlin.math.sqrt((squares/n-(sum/n)*(sum/n)).coerceAtLeast(0.0))
        }
        val photoSpread=tonalSpread(source)
        val diagnostics=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        File(diagnostics,"text-and-photo-original.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }
        try {
            val before=ScanQualityGuard.barcodes(source);assertTrue(before.contains("masahati-preserve-73071446"))
            for(mode in listOf(ScanFilter.AUTO,ScanFilter.CLEAN_WHITE,ScanFilter.CLEAR_TEXT,ScanFilter.PHOTO)) {
                val result=ScanPaperProcessor.process(source,mode).bitmap
                try {
                    assertTrue("QR lost in "+mode,ScanQualityGuard.barcodes(result).containsAll(before))
                    assertTrue("Tiny dot lost in "+mode,Color.red(result.getPixel(150,600))<70)
                    val red=result.getPixel(930,1050);assertTrue("Stamp hue lost in "+mode,Color.red(red)>Color.blue(red)*1.8)
                    val blue=result.getPixel(285,1225);assertTrue("Signature hue lost in "+mode,Color.blue(blue)>Color.red(blue)*1.8)
                    assertTrue("Whitening not applied in "+mode,Color.red(result.getPixel(50,800))>=235)
                    if(mode==ScanFilter.CLEAN_WHITE) assertTrue("Clean White background must reach 250",Color.red(result.getPixel(50,800))>=250)
                    var paper=0;var white=0
                    val floor=if(mode==ScanFilter.CLEAN_WHITE) 250 else if(mode==ScanFilter.PHOTO) 240 else 242
                    for(y in 815 until 1100 step 2) for(x in 80 until 550 step 2) {
                        val original=source.getPixel(x,y)
                        if(Color.red(original)>=170 && kotlin.math.abs(Color.red(original)-Color.blue(original))<=6) {
                            paper++;if(Color.red(result.getPixel(x,y))>=floor) white++
                        }
                    }
                    assertTrue("Grey islands behind bold text in $mode: $white/$paper",paper>1000 && white.toDouble()/paper>=.97)
                    assertTrue("Grayscale photo contrast damaged in $mode",tonalSpread(result)>=photoSpread*.85)
                    if(mode==ScanFilter.CLEAN_WHITE) File(diagnostics,"text-and-photo-clean-white.png").outputStream().use {
                        result.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                    assertTrue("Foreground details lost in "+mode,ScanQualityGuard.detailReasons(source,result,mode).isEmpty())
                } finally { result.recycle() }
            }
        } finally { ScannerTestDiagnostics.publish(diagnostics);source.recycle() }
    }
    @Test fun sharpShadowBoundaryIsWhiteWithoutErasingNearbyNumbersOrColoredInk() {
        val source=sheet();val canvas=Canvas(source)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=27f }
        canvas.drawText("73071446 i j 1,234.56;",320f,850f,paint)
        // Multiplicative, very hard phone shadow crossing text and the signature.
        // Every source pixel, including the ink, is dimmed; no detail is redrawn.
        val row=IntArray(source.width)
        for(y in 0 until source.height) {
            source.getPixels(row,0,row.size,0,y,row.size,1)
            for(x in row.indices) if(x<440) {
                val c=row[x];row[x]=Color.rgb((Color.red(c)*.6).toInt(),(Color.green(c)*.6).toInt(),(Color.blue(c)*.6).toInt())
            }
            source.setPixels(row,0,row.size,0,y,row.size,1)
        }
        val diagnostics=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        File(diagnostics,"sharp-shadow-original.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }
        try {
            ScanOcr(context).use { ocr ->
                val before=ocr.read(source,maxLines=40)
                assertTrue("Fixture number was unreadable before processing",before.text.contains("73071446"))
                for(mode in listOf(ScanFilter.AUTO,ScanFilter.CLEAN_WHITE,ScanFilter.CLEAR_TEXT)) {
                    val processed=ScanPaperProcessor.process(source,mode)
                    val image=processed.bitmap
                    try {
                        File(diagnostics,"sharp-shadow-${mode.name.lowercase()}.png").outputStream().use {
                            image.compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                        val floor=if(mode==ScanFilter.CLEAN_WHITE) 250 else 242
                        for(x in listOf(410,420,430,435,439,440,445,450,460))
                            assertTrue("Shadow halo in $mode at $x: red=${Color.red(image.getPixel(x,800))}, expected >=$floor",Color.red(image.getPixel(x,800))>=floor)
                        assertTrue("Small dot disappeared in $mode",Color.red(image.getPixel(150,600))<70)
                        assertTrue("QR damaged in $mode",ScanQualityGuard.barcodes(image).contains("masahati-preserve-73071446"))
                        val after=ocr.read(image,maxLines=40)
                        val guard=ScanGuardPolicy.evaluate(before.lines.map { it.text to it.confidence },after.lines.map { it.text to it.confidence },emptySet(),emptySet())
                        assertTrue("Numbers or punctuation changed in $mode: ${guard.reasons}",guard.accepted)
                        val details=ScanQualityGuard.detailReasons(source,image,mode)
                        assertTrue("Stamp or signature weakened in $mode: $details",details.isEmpty())
                        val red=image.getPixel(930,1050);assertTrue("Stamp hue lost in $mode",Color.red(red)>Color.blue(red)*1.8)
                        val blue=image.getPixel(285,1225);assertTrue("Signature hue lost in $mode",Color.blue(blue)>Color.red(blue)*1.8)
                    } finally { image.recycle() }
                }
            }
        } finally { ScannerTestDiagnostics.publish(diagnostics);source.recycle() }
    }
    @Test fun qualityGuardIgnoresARealIlluminationStepButRejectsRemovedGrayInk() {
        val before=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888)
        val preserved=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888)
        val removed=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        try {
            val a=Canvas(before);a.drawColor(Color.rgb(202,202,202));paint.color=Color.rgb(105,105,105)
            a.drawRect(0f,0f,350f,900f,paint)
            val b=Canvas(preserved);b.drawColor(Color.WHITE)
            fun ink(canvas: Canvas) {
                paint.color=Color.rgb(65,65,65);paint.textSize=29f;paint.typeface=Typeface.DEFAULT
                repeat(8) { row -> canvas.drawText("Gray ink i j 1,234.56;",35f,100f+row*65f,paint) }
                paint.color=Color.BLACK
                repeat(60) { dot -> canvas.drawCircle(50f+(dot%10)*23f,650f+(dot/10)*25f,2f,paint) }
            }
            ink(a);ink(b)
            assertTrue("Removing illumination alone failed the ink guard",ScanQualityGuard.detailReasons(before,preserved,ScanFilter.CLEAN_WHITE).isEmpty())
            // Keep the small black dots: losing the gray words must still fail.
            val c=Canvas(removed);c.drawBitmap(preserved,0f,0f,null);paint.color=Color.WHITE
            c.drawRect(20f,50f,345f,610f,paint)
            assertTrue("Removed gray text passed the ink guard",ScanQualityGuard.detailReasons(before,removed,ScanFilter.CLEAN_WHITE)
                .contains("انخفضت تفاصيل الحروف والعلامات الصغيرة"))
        } finally { before.recycle();preserved.recycle();removed.recycle() }
    }
    @Test fun cleanWhiteNeutralizesMildAgedPaperTintAndKeepsColoredStampsAndSmallInk() {
        val source=sheet();val row=IntArray(source.width)
        for(y in 0 until source.height) {
            source.getPixels(row,0,row.size,0,y,row.size,1)
            for(x in row.indices) {
                val c=row[x]
                if(kotlin.math.abs(Color.red(c)-Color.blue(c))<12 && Color.red(c)>100)
                    row[x]=Color.rgb((Color.red(c)*1.02).toInt().coerceAtMost(255),(Color.green(c)*.96).toInt(),(Color.blue(c)*.77).toInt())
            }
            source.setPixels(row,0,row.size,0,y,row.size,1)
        }
        try {
            val result=ScanPaperProcessor.process(source,ScanFilter.CLEAN_WHITE).bitmap
            try {
                for(point in listOf(100 to 800,500 to 800)) {
                    val c=result.getPixel(point.first,point.second)
                    assertTrue("Aged paper did not become white",minOf(Color.red(c),Color.green(c),Color.blue(c))>=249)
                }
                assertTrue(Color.red(result.getPixel(150,600))<70)
                assertTrue(ScanQualityGuard.barcodes(result).contains("masahati-preserve-73071446"))
                assertTrue(ScanQualityGuard.detailReasons(source,result,ScanFilter.CLEAN_WHITE).isEmpty())
            } finally { result.recycle() }
        } finally { source.recycle() }
    }
    @Test fun faintPunctuationNearPaperColorDoesNotTurnIntoWhite() {
        val source=sheet();val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(194,194,194) }
        Canvas(source).drawCircle(550f,550f,2f,paint)
        val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        fun saveCrop(image: Bitmap,label: String) {
            val crop=Bitmap.createBitmap(image,530,530,40,40)
            try { File(folder,"faint-dot-$label.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { crop.recycle() }
        }
        try {
            saveCrop(source,"original")
            assertEquals(194,Color.red(source.getPixel(550,550)))
            for(mode in listOf(ScanFilter.AUTO,ScanFilter.CLEAN_WHITE,ScanFilter.CLEAR_TEXT,ScanFilter.PHOTO)) {
                val image=ScanPaperProcessor.process(source,mode).bitmap
                try {
                    val ink=Color.red(image.getPixel(550,550));val paper=Color.red(image.getPixel(560,550))
                    assertTrue("Faint dot vanished in $mode: ink=$ink, paper=$paper",paper-ink>=4)
                    assertTrue("Background did not get cleaned in $mode",paper>=240)
                    assertTrue(ScanQualityGuard.barcodes(image).contains("masahati-preserve-73071446"))
                    saveCrop(image,mode.name.lowercase())
                } finally { image.recycle() }
            }
        } finally { source.recycle();ScannerTestDiagnostics.publish(folder) }
    }
    @Test fun nativePerspectivePreservesQrAndDeskewMarginsStayWhite() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        try {
            val output=ScanSourceImage.perspective(store.source(page),page.quad,maxSide=1600,maxPixels=2_000_000,deskewDegrees=1.0)
            try {
                assertTrue(ScanQualityGuard.barcodes(output).contains("masahati-preserve-73071446"))
                assertTrue(output.width>=source.width);assertTrue(output.height>=source.height);assertEquals(Color.WHITE,output.getPixel(0,0))
            } finally { output.recycle() };store.verifySource(page)
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun hdrSourceSurvivesReopenAndCannotReplaceTheNormalOriginal() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        try {
            val originalHash=page.sourceHash
            val bytes=ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.JPEG,100,bytes)
            store.attachHdr(page,ByteArrayInputStream(bytes.toByteArray()))
            val reopened=ScanSessionStore.open(context,store.id);val saved=reopened.pages.single()
            assertNotNull(saved.hdrSource);reopened.verifyHdr(saved);reopened.verifySource(saved)
            assertEquals(originalHash,saved.sourceHash)
            assertTrue(saved.hdrSource!=saved.source)
            val old=store.hdr(page)!!.readBytes()
            try { store.attachHdr(page,ByteArrayInputStream(bytes.toByteArray()));fail("HDR was overwritten") }
            catch(_: IllegalArgumentException) { assertArrayEquals(old,store.hdr(page)!!.readBytes()) }
            store.hdr(page)!!.appendBytes(byteArrayOf(1))
            try { store.verifyHdr(page);fail("Corrupted HDR was trusted") } catch(_: IllegalStateException) { store.verifySource(page) }
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun bestFrameIsImmutableAndCannotUseAnArbitraryManifestPath() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        try {
            val bytes=ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,bytes)
            store.attachBest(page,ByteArrayInputStream(bytes.toByteArray()))
            val reopened=ScanSessionStore.open(context,store.id);val saved=reopened.pages.single()
            reopened.verifyBest(saved);reopened.verifySource(saved);assertEquals(page.sourceHash,saved.sourceHash)
            try { store.attachBest(page,ByteArrayInputStream(bytes.toByteArray()));fail("Best capture was overwritten") }
            catch(_: IllegalArgumentException) { store.verifyBest(page) }
            val json=JSONObject(store.file("session.json").readText())
            json.getJSONArray("pages").getJSONObject(0).put("best_source","../../other.jpg")
            store.file("session.json").writeText(json.toString())
            try { ScanSessionStore.open(context,store.id);fail("Manifest accepted a path outside its session") }
            catch(_: IllegalArgumentException) { store.verifySource(page) }
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun sharperRealFrameIsAlignedValidatedAndKeepsTheFirstOriginal() {
        // A camera frame includes the whole paper and a surrounding surface.
        // A quad at the exact raster border legitimately fails registration
        // when even subpixel movement would put an edge outside the second frame.
        val paper=sheet();val margin=60
        val sharp=Bitmap.createBitmap(paper.width+2*margin,paper.height+2*margin,Bitmap.Config.ARGB_8888)
        Canvas(sharp).apply { drawColor(Color.rgb(40,48,55));drawBitmap(paper,margin.toFloat(),margin.toFloat(),null) }
        paper.recycle()
        val source=Bitmap.createBitmap(sharp)
        val input=Mat();val blurred=Mat();val store=ScanSessionStore.create(context)
        try {
            Utils.bitmapToMat(source,input);Imgproc.GaussianBlur(input,blurred,Size(5.0,5.0),1.1);Utils.matToBitmap(blurred,source)
            val page=import(store,source)
            val left=margin.toDouble()/(source.width-1);val top=margin.toDouble()/(source.height-1)
            val right=(source.width-margin-1).toDouble()/(source.width-1)
            val bottom=(source.height-margin-1).toDouble()/(source.height-1)
            val boundary=listOf(ScanPoint(left,top),ScanPoint(right,top),ScanPoint(right,bottom),ScanPoint(left,bottom))
            // Production refinement pads six SOURCE pixels outward to preserve
            // the paper. Test that real capture recipe, including its surface
            // context, instead of a tight zero-margin warp with a defocus halo.
            page.quad=DocumentQuad(ScanGeometry.padded(boundary,source.width,source.height,6.0),
                confidence=1.0,origin="camera-fixture-with-production-padding")
            store.save()
            val bytes=ByteArrayOutputStream();sharp.compress(Bitmap.CompressFormat.PNG,100,bytes)
            store.attachBest(page,ByteArrayInputStream(bytes.toByteArray()))
            val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
            val warp=ScanSourceImage.perspective(store.source(page),page.quad,page.turns)
            try {
                File(folder,"camera-before.png").outputStream().use { warp.compress(Bitmap.CompressFormat.PNG,100,it) }
                val aligned=ScanHdrFusion.align(store.source(page),checkNotNull(store.best(page)),page.quad,page.turns,warp,0.0,null)
                try { File(folder,"camera-sharper-aligned.png").outputStream().use { aligned.image.compress(Bitmap.CompressFormat.PNG,100,it) } }
                finally { aligned.image.recycle() }
            } finally { warp.recycle();ScannerTestDiagnostics.publish(folder) }
            ScannerEngine(context).use { engine -> engine.process(store,page).use { result ->
                assertTrue("Safer sharper capture was not adopted: ${result.report}",result.report.getBoolean("best_frame_applied"))
                assertTrue(result.report.getString("ocr_text").contains("73071446"))
                assertTrue(ScanQualityGuard.barcodes(result.after).contains("masahati-preserve-73071446"))
                assertTrue(result.before!==result.after)
                File(folder,"camera-final.png").outputStream().use { result.after.compress(Bitmap.CompressFormat.PNG,100,it) }
                File(folder,"camera-report.json").writeText(result.report.toString(2));ScannerTestDiagnostics.publish(folder)
            } }
            store.verifySource(page);store.verifyBest(page)
        } finally { input.release();blurred.release();source.recycle();sharp.recycle();store.directory.deleteRecursively() }
    }
    @Test fun extraFrameWithAnOffscreenPaperEdgeIsRejectedWithoutClamping() {
        val source=sheet();val cropped=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        val store=ScanSessionStore.create(context)
        try {
            Canvas(cropped).apply { drawColor(Color.rgb(202,202,197));drawBitmap(source,-45f,0f,null) }
            val page=import(store,source);val bytes=ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.PNG,100,bytes)
            store.attachBest(page,ByteArrayInputStream(bytes.toByteArray()))
            val attempt=runCatching { ScanHdrFusion.align(store.source(page),checkNotNull(store.best(page)),
                page.quad,page.turns,source,0.0,null) }
            attempt.getOrNull()?.image?.recycle()
            assertTrue("A camera frame that lost a paper edge was adopted",attempt.isFailure)
            assertTrue("The missing edge must be rejected by the frame bounds guard: ${attempt.exceptionOrNull()}",
                attempt.exceptionOrNull()?.message?.contains("تغير الإطار أو اختفت حافة")==true)
            store.verifySource(page);store.verifyBest(page)
        } finally { source.recycle();cropped.recycle();store.directory.deleteRecursively() }
    }
    @Test fun captureGuardAcceptsSharpenedInkButRejectsRemovedPunctuationAndSignature() {
        val sharp=sheet();val blurred=Bitmap.createBitmap(sharp)
        val erased=Bitmap.createBitmap(sharp)
        val input=Mat();val output=Mat()
        try {
            Utils.bitmapToMat(blurred,input);Imgproc.GaussianBlur(input,output,Size(5.0,5.0),1.1);Utils.matToBitmap(output,blurred)
            assertTrue("A sharper version of the same content was rejected",
                ScanCaptureContentGuard.reasons(blurred,sharp).isEmpty())
            val paint=Paint().apply { color=Color.rgb(175,175,170) }
            Canvas(erased).drawRect(140f,590f,160f,610f,paint)
            assertFalse("Removed tiny punctuation was accepted",ScanCaptureContentGuard.reasons(blurred,erased).isEmpty())
            Canvas(erased).drawBitmap(sharp,0f,0f,null);paint.color=Color.rgb(202,202,197)
            Canvas(erased).drawRect(135f,1185f,440f,1270f,paint)
            assertFalse("Removed blue signature was accepted",ScanCaptureContentGuard.reasons(blurred,erased).isEmpty())
        } finally { input.release();output.release();sharp.recycle();blurred.recycle();erased.recycle() }
    }
    @Test fun ghostingRejectsDoubledOrRemovedTextAndKeepsAnUnchangedImage() {
        val source=sheet();val doubled=Bitmap.createBitmap(source);val removed=Bitmap.createBitmap(source)
        try {
            assertTrue(ScanHdrFusion.ghosting(source,source).acceptable())
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=34f }
            val canvas=Canvas(doubled)
            repeat(5) { row -> canvas.drawText("73071446 duplicated ghost",82f,174f+row*130f,paint) }
            assertFalse("New ghost letters were accepted",ScanHdrFusion.ghosting(source,doubled).acceptable())
            paint.color=Color.rgb(202,202,197);Canvas(removed).drawRect(40f,115f,1050f,550f,paint)
            assertFalse("Removed original letters were accepted",ScanHdrFusion.ghosting(source,removed).acceptable())
        } finally { source.recycle();doubled.recycle();removed.recycle() }
    }
    @Test fun dewarpCoverageProtectsSignaturesOutsideRecognizedText() {
        val source=Bitmap.createBitmap(900,1300,Bitmap.Config.ARGB_8888)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLUE;strokeWidth=4f }
        try {
            Canvas(source).apply { drawColor(Color.WHITE);drawLine(12f,1240f,280f,1250f,paint) }
            fun grid(inset: Float)=FloatArray(2790) { i ->
                val offset=i%1395;val value=if(i<1395) offset%31/30f else offset/31/44f
                (value*2-1)*(1-inset)
            }
            val unchanged=ScanDewarpGuard.coverage(source,grid(0f))
            assertTrue("Identity grid lost source ink",unchanged.acceptable());assertTrue(unchanged.inkPixels>100)
            val cropped=ScanDewarpGuard.coverage(source,grid(.16f))
            assertFalse("Grid removed an edge signature that OCR need not recognize",cropped.acceptable())
            assertTrue(cropped.damagedComponents>0)
        } finally { source.recycle() }
    }
    @Test fun printedBarcodeSurvivesEveryColorDocumentMode() {
        val source=sheet()
        val barcode=com.google.zxing.MultiFormatWriter().encode("73071446",BarcodeFormat.CODE_128,500,140)
        try {
            for(y in 0 until barcode.height) for(x in 0 until barcode.width)
                source.setPixel(50+x,1300+y,if(barcode[x,y]) Color.BLACK else Color.WHITE)
            assertTrue("Source barcode was unreadable",ScanQualityGuard.barcodes(source).contains("73071446"))
            for(mode in listOf(ScanFilter.AUTO,ScanFilter.CLEAN_WHITE,ScanFilter.CLEAR_TEXT,ScanFilter.PHOTO,ScanFilter.BLACK_WHITE)) {
                val image=ScanPaperProcessor.process(source,mode).bitmap
                try { assertTrue("Barcode damaged by $mode",ScanQualityGuard.barcodes(image).contains("73071446")) }
                finally { image.recycle() }
            }
        } finally { source.recycle() }
    }
    @Test fun curvedPageRunsRealUvDocOrRecordsItsSafeFallback() {
        val flat=sheet();val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=22f }
        repeat(6) { row -> Canvas(flat).drawText("Official document 73071446; text, signature and stamps.",70f,345f+row*40f,paint) }
        val source=Bitmap.createBitmap(flat.width,flat.height,Bitmap.Config.ARGB_8888)
        val input=Mat();val output=Mat();val mx=Mat(flat.height,flat.width,org.opencv.core.CvType.CV_32F);val my=Mat(flat.height,flat.width,org.opencv.core.CvType.CV_32F)
        val store=ScanSessionStore.create(context);val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
        try {
            val xs=FloatArray(flat.width*flat.height);val ys=FloatArray(xs.size)
            for(y in 0 until flat.height) for(x in 0 until flat.width) {
                val u=x.toDouble()/(flat.width-1)*2-1;val i=y*flat.width+x
                xs[i]=x.toFloat();ys[i]=(y-48*u*u).toFloat()
            }
            Utils.bitmapToMat(flat,input);mx.put(0,0,xs);my.put(0,0,ys)
            Imgproc.remap(input,output,mx,my,Imgproc.INTER_CUBIC,org.opencv.core.Core.BORDER_CONSTANT,org.opencv.core.Scalar(255.0,255.0,255.0,255.0))
            Utils.matToBitmap(output,source)
            val geometry=ScanPageGeometry.analyze(source)
            assertTrue("Curved text rows were not detected: $geometry",geometry.curved && geometry.curveConfidence>=.6)
            val page=import(store,source)
            ScannerEngine(context).use { engine -> engine.process(store,page).use { result ->
                assertTrue(result.report.getBoolean("curved"))
                assertTrue("UVDoc neither ran nor recorded a safe rejection",result.report.getBoolean("dewarp_applied") ||
                    result.report.has("dewarp_fallback") || result.warnings.any { it.contains("التسطيح") })
                assertTrue(result.report.getString("ocr_text").contains("73071446"))
                File(folder,"curved-page-diagnostic.json").writeText(result.report.toString(2))
                File(folder,"curved-page-original.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) }
                File(folder,"curved-page-final.png").outputStream().use { result.after.compress(Bitmap.CompressFormat.PNG,100,it) }
            } }
            store.verifySource(page)
        } finally {
            listOf(input,output,mx,my).forEach { it.release() };source.recycle();flat.recycle();store.directory.deleteRecursively()
            ScannerTestDiagnostics.publish(folder)
        }
    }
    @Test fun hdrRegistrationPreservesGeometryAndQrOnTheSameCapturedPixels() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        try {
            val bytes=ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.PNG,100,bytes)
            store.attachHdr(page,ByteArrayInputStream(bytes.toByteArray()))
            val quad=DocumentQuad.inset(.02)
            val base=ScanSourceImage.perspective(store.source(page),quad,maxSide=1600,maxPixels=2_000_000)
            try {
                val aligned=ScanHdrFusion.align(store.source(page),store.hdr(page)!!,quad,0,base,0.0,null)
                try {
                    assertEquals(base.width,aligned.image.width);assertEquals(base.height,aligned.image.height)
                    assertTrue(aligned.inlierFraction>.95)
                    assertTrue(ScanHdrFusion.ghosting(base,aligned.image).acceptable())
                    assertTrue(ScanQualityGuard.barcodes(aligned.image).contains("masahati-preserve-73071446"))
                } finally { aligned.image.recycle() }
            } finally { base.recycle() }
            store.verifySource(page);store.verifyHdr(page)
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun paddleGuardReadsNumbersAndNeverChangesRawCapture() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        try {
            ScanOcr(context).use { assertTrue(it.read(source).text.contains("73071446")) }
            ScannerEngine(context).use { engine -> engine.process(store,page).use { result ->
                assertTrue(result.report.getString("ocr_text").contains("73071446"))
                assertTrue(ScanQualityGuard.barcodes(result.after).contains("masahati-preserve-73071446"))
            } }
            store.verifySource(page);assertTrue(page.ready);assertTrue(store.processed(page).isFile)
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun savedPdfRendersAndBackupRestoresImmutableOriginal() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source)
        val pdf=File(context.filesDir,"documents/scanner-regression-"+System.nanoTime()+".pdf").apply { parentFile?.mkdirs() }
        val db=MasahatiDatabase(context);val spaces=mutableListOf<Long>();var restored: ScanSessionStore?=null;var importedFile: File?=null
        try {
            val capture=ByteArrayOutputStream();source.compress(Bitmap.CompressFormat.JPEG,100,capture)
            store.attachHdr(page,ByteArrayInputStream(capture.toByteArray()))
            store.attachBest(page,ByteArrayInputStream(capture.toByteArray()))
            store.saveBitmap(source,store.processed(page));page.ready=true;page.review=false;store.save();ScanPdfExporter.export(context,store,pdf)
            ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { reader ->
                assertEquals(1,reader.pageCount);reader.openPage(0).use { p ->
                    val image=Bitmap.createBitmap(1100,1550,Bitmap.Config.ARGB_8888)
                    try { p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);assertTrue(ScanQualityGuard.barcodes(image).contains("masahati-preserve-73071446")) }
                    finally { image.recycle() }
                }
            } }
            val title="Scanner backup regression "+System.nanoTime();val space=db.createSpace(title);spaces.add(space)
            db.insertFile(space,"user","scanner.pdf",pdf.absolutePath,"application/pdf","73071446")
            val bytes=ByteArrayOutputStream();AlphaExporter.export(context,db,bytes)
            assertTrue(AlphaImporter.importZip(context,db,ByteArrayInputStream(bytes.toByteArray())).files>=1)
            val copied=db.allMessagesIncludingTrash().last { it.displayName=="scanner.pdf" && it.filePath!=pdf.absolutePath }
            spaces.add(copied.spaceId);importedFile=File(copied.filePath!!)
            restored=ScanSessionStore.forDocument(context,importedFile!!);assertNotNull(restored)
            assertEquals(page.sourceHash,restored!!.pages.single().sourceHash);restored!!.verifySource(restored!!.pages.single())
            assertEquals(page.hdrHash,restored!!.pages.single().hdrHash);restored!!.verifyHdr(restored!!.pages.single())
            assertEquals(page.bestHash,restored!!.pages.single().bestHash);restored!!.verifyBest(restored!!.pages.single())
        } catch(error: Exception) {
            val diagnostic=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
            if(pdf.isFile) pdf.copyTo(File(diagnostic,"pdf-compatibility-api-"+android.os.Build.VERSION.SDK_INT+".pdf"),true)
            throw error
        } finally { source.recycle();store.directory.deleteRecursively();restored?.directory?.deleteRecursively()
            pdf.delete();importedFile?.delete();spaces.distinct().forEach { db.deleteSpace(it) };db.close() }
    }
    @Test fun cropEditorResumesAfterActivityRecreation() {
        val source=sheet();val store=ScanSessionStore.create(context);val page=import(store,source);source.recycle()
        try {
            val intent=Intent(context,ProfessionalScannerActivity::class.java).putExtra(ProfessionalScannerActivity.EXTRA_SESSION,store.id)
            ActivityScenario.launch<ProfessionalScannerActivity>(intent).use { scenario ->
                scenario.onActivity { assertNotNull(find(it.window.decorView,"scanner-apply-crop")) };scenario.recreate()
                scenario.onActivity { assertNotNull(find(it.window.decorView,"scanner-apply-crop")) }
            };ScanSessionStore.open(context,store.id).verifySource(page)
        } finally { store.directory.deleteRecursively() }
    }
    @Test fun inkOnWhitePaperDoesNotCountAsGlareOrBlockCapture() {
        val source=Bitmap.createBitmap(900,1300,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=22f }
            repeat(42) { row -> canvas.drawText("Official record 73071446, i j 1,234.56; 2026.",55f,65f+row*28f,paint) }
            val quality=ScanQuality.analyze(source,DocumentQuad.inset(.03))
            assertTrue("Text was misclassified as glare: "+quality,quality.acceptable())
            assertFalse(ScanQuality.analyze(source,DocumentQuad.inset(.03),focused=false).acceptable())
        } finally { source.recycle() }
    }
    @Test fun fiftyMegapixelSourceUsesBoundedBuffersAndKeepsOriginal() {
        val store=ScanSessionStore.create(context)
        val page=InstrumentationRegistry.getInstrumentation().context.assets.open("scanner-fixtures/large-50mp.jpg").use { store.import(it) }
        page.quad=DocumentQuad.inset(0.0).copy(confidence=1.0,origin="manual");page.review=false;store.save()
        val sampling=AtomicBoolean(true);val peakPss=AtomicLong(0);val peakHeapNative=AtomicLong(0)
        val sampler=thread(name="scanner-memory-sampler",isDaemon=true) {
            while(sampling.get()) {
                val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
                val pss=memory.totalPss.toLong()*1024
                peakPss.updateAndGet { maxOf(it,pss) }
                val heap=Runtime.getRuntime().let { it.totalMemory()-it.freeMemory() }+Debug.getNativeHeapAllocatedSize()
                peakHeapNative.updateAndGet { maxOf(it,heap) };Thread.sleep(25)
            }
        }
        val started=System.nanoTime();val diagnostic=JSONObject().put("synthetic",true).put("source_pixels",50_000_000)
        try {
            val info=ScanSourceImage.info(store.source(page));assertEquals(50_000_000L,info.rawWidth.toLong()*info.rawHeight)
            ScannerEngine(context).use { engine -> engine.process(store,page).use { result ->
                assertTrue("Output exceeded the bounded pixel budget",result.after.width.toLong()*result.after.height<=7_000_000)
                assertTrue(result.report.getString("ocr_text").contains("73071446"))
                val red=result.after.getPixel(result.after.width/25,result.after.height/25)
                val blue=result.after.getPixel(result.after.width*96/100,result.after.height*94/100)
                assertTrue("Top-left content lost",Color.red(red)>Color.blue(red)*1.5)
                assertTrue("Bottom-right content lost",Color.blue(blue)>Color.red(blue)*1.5)
                diagnostic.put("output_width",result.after.width).put("output_height",result.after.height).put("pipeline",result.report)
            } }
            store.verifySource(page);assertTrue(page.ready)
        } finally {
            sampling.set(false);sampler.join(1500)
            diagnostic.put("elapsed_ms",(System.nanoTime()-started)/1_000_000.0)
                .put("sampled_peak_pss_bytes",peakPss.get()).put("sampled_peak_heap_native_bytes",peakHeapNative.get())
                .put("memory_scope","25 ms samples on emulator; not a physical-device maximum")
            val folder=File(context.filesDir,"scanner-benchmark-report").apply { mkdirs() }
            File(folder,"50mp-memory-api-"+android.os.Build.VERSION.SDK_INT+".json").writeText(diagnostic.toString(2))
            Log.i("ScannerMemory",diagnostic.toString());ScannerTestDiagnostics.publish(folder);store.directory.deleteRecursively()
        }
    }
    @Test fun nativeEdgeRefinementFindsPaperRatherThanPrintedRectangle() {
        val source=Bitmap.createBitmap(1400,1800,Bitmap.Config.ARGB_8888)
        val store=ScanSessionStore.create(context)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.rgb(55,70,60))
            val truth=listOf(ScanPoint(112.0,94.0),ScanPoint(1302.0,136.0),ScanPoint(1248.0,1698.0),ScanPoint(86.0,1652.0))
            val path=Path();truth.forEachIndexed { i,p -> if(i==0) path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat()) };path.close()
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(242,242,237) }
            canvas.drawPath(path,paint)
            paint.color=Color.BLACK;paint.style=Paint.Style.STROKE;paint.strokeWidth=2f
            canvas.drawRect(155f,190f,1180f,1560f,paint)
            paint.style=Paint.Style.FILL;paint.textSize=31f
            repeat(28) { row -> canvas.drawText("Record 73071446, punctuation i j.",185f,270f+row*37f,paint) }
            val page=import(store,source)
            val normalized=truth.map { ScanPoint(it.x/1399,it.y/1799) }
            val coarse=normalized.map { p -> ScanPoint(p.x+(if(p.x<.5) 25 else -25)/1399.0,p.y+(if(p.y<.5) 25 else -25)/1799.0) }
            val result=FullResolutionEdgeRefiner.refine(store.source(page),DocumentQuad(coarse,1.0))
            assertEquals("Four physical paper edges were not fitted",4,result.acceptedEdges)
            assertFalse(result.needsManualReview)
            val refined=result.quad.points.map { ScanPoint(it.x*1399,it.y*1799) }
            val boundary=result.boundaryQuad.points.map { ScanPoint(it.x*1399,it.y*1799) }
            assertTrue("Physical boundary is inaccurate before the explicit safety margin: "+boundary,
                boundary.zip(truth).all { (a,b) -> a.distance(b)<3 })
            assertTrue("Refined corners missed physical paper edges: "+refined,
                refined.zip(truth).all { (a,b) -> a.distance(b)<12 })
            val inward=truth.maxOf { p -> refined.indices.maxOf { e ->
                val a=refined[e];val b=refined[(e+1)%4]
                -((b.x-a.x)*(p.y-a.y)-(b.y-a.y)*(p.x-a.x))/a.distance(b)
            } }
            assertTrue("Crop cut into known paper: "+inward,inward<=.75)
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun threeClearEdgesImproveTheSuggestionButNeverApproveAMissingFourthEdge() {
        val source=Bitmap.createBitmap(1400,1800,Bitmap.Config.ARGB_8888)
        val store=ScanSessionStore.create(context)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.rgb(55,70,60))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(242,242,237) }
            // The bottom physical edge is outside the image. The coarse model
            // suggests one; a precise fit of the other three cannot validate it.
            canvas.drawRect(100f,100f,1300f,1800f,paint)
            val page=import(store,source)
            val coarse=DocumentQuad(listOf(ScanPoint(118.0/1399,118.0/1799),ScanPoint(1282.0/1399,118.0/1799),
                ScanPoint(1282.0/1399,1700.0/1799),ScanPoint(118.0/1399,1700.0/1799)),1.0)
            val result=FullResolutionEdgeRefiner.refine(store.source(page),coarse)
            assertEquals(3,result.acceptedEdges)
            assertTrue("A missing edge must require review",result.needsManualReview)
            assertEquals("native-three-edge-review",result.boundaryQuad.origin)
            val boundary=result.boundaryQuad.points.map { ScanPoint(it.x*1399,it.y*1799) }
            assertTrue("Clear top-left corner was not refined",boundary[0].distance(ScanPoint(100.0,100.0))<3)
            assertTrue("Clear top-right corner was not refined",boundary[1].distance(ScanPoint(1300.0,100.0))<3)
            assertEquals("Unknown bottom line must stay a suggestion",1700.0,boundary[2].y,1e-4)
            store.verifySource(page)
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun straightButBlurredPaperEdgesRequireManualReview() {
        assertTrue(OpenCvDocumentRectifier.isAvailable())
        val source=Bitmap.createBitmap(1100,1550,Bitmap.Config.ARGB_8888)
        val mat=Mat();val store=ScanSessionStore.create(context)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.rgb(55,70,60))
            val paint=Paint().apply { color=Color.rgb(242,242,237) }
            canvas.drawRect(110f,90f,990f,1440f,paint)
            Utils.bitmapToMat(source,mat);Imgproc.GaussianBlur(mat,mat,Size(0.0,0.0),6.0);Utils.matToBitmap(mat,source)
            val page=import(store,source)
            val coarse=DocumentQuad(listOf(ScanPoint(124.0/1099,104.0/1549),ScanPoint(976.0/1099,104.0/1549),
                ScanPoint(976.0/1099,1426.0/1549),ScanPoint(124.0/1099,1426.0/1549)),1.0)
            val result=FullResolutionEdgeRefiner.refine(store.source(page),coarse)
            assertEquals("Blurred straight edges should still have coherent geometric fits",4,result.acceptedEdges)
            assertTrue("A straight line residual must not hide a broad physical transition",result.residualPixels.all { it<2.0 })
            assertTrue("Source-pixel transition uncertainty was not measured",result.transitionWidthsPixels.all { it>6.0 })
            assertTrue("Broad paper edges cannot be accepted as precise automatically",result.needsManualReview)
            assertEquals("Do not hide blur by increasing the crop margin",6.0,result.paddingPixels,0.0)
            store.verifySource(page)
        } finally { mat.release();source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun multiPagePdfKeepsPortraitAndLandscapeGeometry() {
        val source=sheet();val landscape=Bitmap.createBitmap(source,0,0,source.width,source.height,Matrix().apply { postRotate(90f) },true)
        val store=ScanSessionStore.create(context);val target=File(context.cacheDir,"scanner-multi-"+System.nanoTime()+".pdf")
        try {
            for(image in listOf(source,landscape)) {
                val page=import(store,image);store.saveBitmap(image,store.processed(page));page.ready=true;page.review=false
            }
            store.save();ScanPdfExporter.export(context,store,target)
            ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(2,pdf.pageCount)
                    for(i in 0..1) pdf.openPage(i).use { page ->
                        assertEquals(i==0,page.height>page.width)
                        val input=if(i==0) source else landscape
                        val rendered=Bitmap.createBitmap(input.width,input.height,Bitmap.Config.ARGB_8888)
                        try {
                            page.render(rendered,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue(ScanQualityGuard.barcodes(rendered).contains("masahati-preserve-73071446"))
                        } finally { rendered.recycle() }
                    }
                }
            }
            store.pages.forEach(store::verifySource)
        } finally { source.recycle();landscape.recycle();store.directory.deleteRecursively();target.delete() }
    }
    @Test fun sourceReferenceRemovedByCropIsRejectedBeforeAnyFilter() {
        val source=Bitmap.createBitmap(1000,1400,Bitmap.Config.ARGB_8888)
        val store=ScanSessionStore.create(context)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=42f }
            canvas.drawText("REFERENCE 73071446",80f,150f,paint)
            paint.textSize=30f
            repeat(8) { row -> canvas.drawText("Official document content remains intact.",80f,400f+row*80f,paint) }
            val page=import(store,source)
            page.filter=ScanFilter.ORIGINAL
            page.report.put("model_polygon",JSONArray().apply {
                DocumentQuad.inset(0.0).points.forEach { put(JSONArray().put(it.x).put(it.y)) }
            })
            page.quad=DocumentQuad(listOf(ScanPoint(0.0,.2),ScanPoint(1.0,.2),ScanPoint(1.0,1.0),ScanPoint(0.0,1.0)),1.0)
            store.save()
            ScannerEngine(context).use { engine ->
                var rejected=false
                try { engine.process(store,page).use { } }
                catch(error: IllegalStateException) { rejected=error.message.orEmpty().contains("قرب الحافة") }
                assertTrue("Crop silently removed a readable original reference row",rejected)
                assertFalse(page.ready);assertTrue(page.review);store.verifySource(page)
                page.quad=DocumentQuad.inset(0.0).copy(confidence=1.0);page.review=false;store.save()
                engine.process(store,page).use { assertTrue(it.report.getString("ocr_text").contains("73071446")) }
                assertTrue(page.ready);store.verifySource(page)
            }
        } finally { source.recycle();store.directory.deleteRecursively() }
    }
    @Test fun realDocumentModelCanReachStableAutoShutterOnClearFramedPaper() {
        val source=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(source);canvas.drawColor(Color.rgb(45,50,55))
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE }
            canvas.drawRect(170f,130f,1030f,1450f,paint)
            paint.color=Color.BLACK;paint.textSize=30f
            repeat(30) { row -> canvas.drawText("Official record 73071446, clear document.",210f,230f+row*35f,paint) }
            val detection=DocQuadDetector(context).use { it.detect(source) }
            val quad=requireNotNull(detection.quad)
            assertTrue("Publisher model score blocked a clearly framed paper: "+quad.confidence,quad.confidence>=.78)
            val quality=ScanQuality.analyze(source,quad)
            assertTrue("Clear framed paper failed quality checks",quality.acceptable())
            val gate=StableCaptureGate();var eligible=false
            repeat(8) { frame -> eligible=gate.observe(quad,quality,10_000L+frame*150) }
            assertTrue("Real model output could not reach stable auto capture",eligible)
            assertFalse(gate.observe(quad,quality.copy(focused=false),12_000L))
        } finally { source.recycle() }
    }
    private fun find(v: View,tag: String): View? {
        if(v.tag==tag) return v
        if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),tag)?.let { return it };return null
    }
}
