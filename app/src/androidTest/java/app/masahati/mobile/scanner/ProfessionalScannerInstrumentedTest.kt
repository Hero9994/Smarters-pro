package app.masahati.mobile.scanner

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.masahati.mobile.AlphaExporter
import app.masahati.mobile.AlphaImporter
import app.masahati.mobile.MasahatiDatabase
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*

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
                    assertTrue("Foreground details lost in "+mode,ScanQualityGuard.detailReasons(source,result,mode).isEmpty())
                } finally { result.recycle() }
            }
        } finally { source.recycle() }
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
    private fun find(v: View,tag: String): View? {
        if(v.tag==tag) return v
        if(v is ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i),tag)?.let { return it };return null
    }
}
