package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.googlecode.tesseract.android.TessBaseAPI
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** These assertions run the real native OCR engine, not a mock or a text fixture. */
@RunWith(AndroidJUnit4::class)
class DocumentReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun arabicPage(): Bitmap = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 86f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        listOf("عقد إيجار المنزل", "اسم المستأجر أحمد محمد", "رقم العقد 7319", "تاريخ الانتهاء 30.09.2027")
            .forEachIndexed { i, line -> canvas.drawText(line, 1670f, 210f + i * 210f, paint) }
    }

    private fun assertArabicText(text: String) {
        assertTrue("Arabic words were lost: $text", text.contains("عقد") && (text.contains("محمد") || text.contains("المنزل")))
        assertTrue("Reference number was lost: $text", text.contains("7319"))
        assertTrue("Expiry date was lost or reordered: $text", text.contains("30.09.2027"))
    }

    @Test fun readsActualArabicPixelsWithoutCloudAccess() {
        val bitmap = arabicPage()
        try {
            LocalDocumentReader(context).use { reader ->
                val result = reader.readBitmap(bitmap)
                if (!result.text.contains("7319")) {
                    // Only this synthetic fixture is logged, never a user's document. Keep
                    // native segmentation diagnostics with the failed emulator test report.
                    val recognize = LocalDocumentReader::class.java.getDeclaredMethod(
                        "recognize", Bitmap::class.java, Integer.TYPE, java.lang.Long.TYPE
                    ).apply { isAccessible = true }
                    for (mode in listOf(TessBaseAPI.PageSegMode.PSM_AUTO,
                        TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)) {
                        Log.w("MasahatiOCR", "Synthetic fixture mode $mode: ${recognize.invoke(reader, bitmap, mode, 8000L)}")
                    }
                    Log.w("MasahatiOCR", "Synthetic fixture reading note: ${result.note}")
                }
                assertArabicText(result.text)
            }
        }
        finally { bitmap.recycle() }
    }

    @Test fun importedJpegHonorsCameraOrientation() {
        val bitmap = arabicPage()
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(-90f) }, true)
        val file = File.createTempFile("camera-", ".jpg", context.cacheDir)
        try {
            file.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            LocalDocumentReader(context).use { assertArabicText(it.readImage(file).text) }
        } finally { rotated.recycle(); bitmap.recycle(); file.delete() }
    }

    @Test fun importsScannedPdfThroughActivityAndIndexesItWithoutCloudConsent() {
        context.deleteDatabase("masahati_v05.db")
        val db = MasahatiDatabase(context)
        val space = db.createSpace("قراءة محلية")
        val file = makeMixedPdf()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("openSpace", java.lang.Long.TYPE)
                        .apply { isAccessible = true }.invoke(activity, space)
                    MainActivity::class.java.getDeclaredMethod("handlePickedFile", Uri::class.java, java.lang.Boolean::class.java)
                        .apply { isAccessible = true }.invoke(activity, uri, false)
                }
                val deadline = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < deadline && db.listMessages(space).none { it.role == "assistant" }) Thread.sleep(100)
                val saved = db.listMessages(space).single { it.kind == "file" }
                assertArabicText(saved.ocrText.orEmpty())
                assertFalse(saved.cloudAnalysisAllowed)
                assertTrue(File(saved.filePath!!).isFile)
                assertTrue(db.search("7319", 10).any { it.id == saved.id })
                assertTrue(db.listMessages(space).any { it.text.contains("بدون إرساله للتحليل السحابي") })
            }
        } finally { db.close(); context.deleteDatabase("masahati_v05.db"); file.delete() }
    }

    @Test fun keepsGermanTextAndOcrReadsScannedAndMixedPdfPages() {
        val file = makeMixedPdf()
        try {
            LocalDocumentReader(context).use { reader ->
                val result = reader.readPdf(file)
                assertEquals(3, result.totalPages)
                assertEquals(3, result.processedPages)
                assertEquals("PDF reading failed: ${result.note}\n${result.text}", 2, result.ocrPages)
                assertTrue(result.text.contains("Vertragsende 30.09.2027"))
                assertTrue(result.text.contains("Mixed page header"))
                val scanned = result.text.substringAfter("صفحة 2:").substringBefore("صفحة 3:")
                val mixed = result.text.substringAfter("صفحة 3:")
                if (!mixed.contains("7319")) logSyntheticMixedPage(reader, file)
                assertArabicText(scanned)
                assertArabicText(mixed)
            }
        } finally { file.delete() }
    }

    private fun logSyntheticMixedPage(reader: LocalDocumentReader, file: File) {
        PDDocument.load(file).use { document ->
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val render = LocalDocumentReader::class.java.getDeclaredMethod(
                        "renderPdfPage", PDDocument::class.java, PdfRenderer::class.java, Integer.TYPE
                    ).apply { isAccessible = true }
                    val bitmap = render.invoke(reader, document, renderer, 2) as Bitmap
                    val recognize = LocalDocumentReader::class.java.getDeclaredMethod(
                        "recognize", Bitmap::class.java, Integer.TYPE, java.lang.Long.TYPE
                    ).apply { isAccessible = true }
                    val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    try {
                        val text = Tasks.await(latin.process(InputImage.fromBitmap(bitmap, 0)), 8, TimeUnit.SECONDS)
                        for (line in text.textBlocks.flatMap { it.lines }.filter { it.text.any(Char::isDigit) }.take(6)) {
                            val box = line.boundingBox ?: continue
                            val pad = (box.height() / 2).coerceIn(12, 48)
                            val top = (box.top - pad).coerceIn(0, bitmap.height - 1)
                            val bottom = (box.bottom + pad).coerceIn(top + 1, bitmap.height)
                            Log.w("MasahatiOCR", "Synthetic mixed numeric row: ${line.text}, box=$box")
                            val strip = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                            try {
                                for (mode in listOf(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                                    TessBaseAPI.PageSegMode.PSM_SINGLE_LINE, 13 /* raw line */)) {
                                    Log.w("MasahatiOCR", "Synthetic numeric strip mode $mode: ${recognize.invoke(reader, strip, mode, 4000L)}")
                                }
                            } finally { if (strip !== bitmap) strip.recycle() }
                        }
                    } finally { latin.close(); bitmap.recycle() }
                }
            }
        }
    }

    @Test fun reportsPageAndOcrLimitsWithoutPretendingWholeDocumentWasRead() {
        val file = makeMixedPdf()
        try {
            LocalDocumentReader(context).use { reader ->
                val limited = reader.readPdf(file, maxPages = 1)
                assertEquals(1, limited.processedPages)
                assertTrue(limited.note.orEmpty().contains("1 من 3"))
                val noOcr = reader.readPdf(file, maxOcrPages = 0)
                assertTrue(noOcr.text.contains("Vertragsende"))
                assertTrue(noOcr.note.orEmpty().contains("لم تُقرأ"))
            }
        } finally { file.delete() }
    }

    @Test fun blankImageAndBrokenPdfDoNotInventContent() {
        val bitmap = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val broken = File.createTempFile("broken-", ".pdf", context.cacheDir).apply { writeText("%PDF-1.4 broken") }
        try {
            LocalDocumentReader(context).use { reader ->
                val blank = reader.readBitmap(bitmap)
                assertEquals("", blank.text)
                assertNotNull(blank.note)
                val pdf = reader.readPdf(broken)
                assertEquals("", pdf.text)
                assertNotNull(pdf.note)
            }
        } finally { bitmap.recycle(); broken.delete() }
    }

    @Test fun unsuccessfulRereadPreservesExistingTextAndConsent() {
        context.deleteDatabase("masahati_v05.db")
        val db = MasahatiDatabase(context)
        val space = db.createSpace("قراءة محلية")
        val file = File.createTempFile("broken-", ".pdf", context.cacheDir).apply { writeText("%PDF-1.4 broken") }
        val id = db.insertFile(space, "user", "old.pdf", file.absolutePath, "application/pdf", "previous readable text")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("rereadDocument", MessageRow::class.java)
                        .apply { isAccessible = true }.invoke(activity, db.getMessage(id))
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && db.getMessage(id)?.extractionNote == null) Thread.sleep(50)
                assertEquals("previous readable text", db.getMessage(id)!!.ocrText)
                assertFalse(db.getMessage(id)!!.cloudAnalysisAllowed)
                assertTrue(db.getMessage(id)!!.extractionNote.orEmpty().contains("احتفظت بالنص السابق"))
            }
        } finally { db.close(); context.deleteDatabase("masahati_v05.db"); file.delete() }
    }

    private fun makeMixedPdf(): File {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("mixed-", ".pdf", context.cacheDir)
        val bitmap = arabicPage()
        try {
            PDDocument().use { doc ->
                val image = LosslessFactory.createFromImage(doc, bitmap)
                repeat(3) { index ->
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { content ->
                        if (index != 1) {
                            content.beginText()
                            content.setFont(PDType1Font.HELVETICA, 18f)
                            content.newLineAtOffset(40f, 740f)
                            content.showText(if (index == 0) "Vertragsende 30.09.2027." else "Mixed page header")
                            content.endText()
                        }
                        if (index > 0) content.drawImage(image, 15f, 190f, 580f, 390f)
                    }
                }
                doc.save(file)
            }
        } finally { bitmap.recycle() }
        return file
    }
}
