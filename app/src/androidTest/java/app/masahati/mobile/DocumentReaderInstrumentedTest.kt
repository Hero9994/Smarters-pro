package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
    }

    @Test fun readsActualArabicPixelsWithoutCloudAccess() {
        val bitmap = arabicPage()
        try { LocalDocumentReader(context).use { assertArabicText(it.readBitmap(bitmap).text) } }
        finally { bitmap.recycle() }
    }

    @Test fun keepsGermanTextAndOcrReadsScannedAndMixedPdfPages() {
        val file = makeMixedPdf()
        try {
            LocalDocumentReader(context).use { reader ->
                val result = reader.readPdf(file)
                assertEquals(3, result.totalPages)
                assertEquals(3, result.processedPages)
                assertEquals(2, result.ocrPages)
                assertTrue(result.text.contains("Vertragsende 30.09.2027"))
                assertTrue(result.text.contains("Mixed page header"))
                val scanned = result.text.substringAfter("صفحة 2:").substringBefore("صفحة 3:")
                val mixed = result.text.substringAfter("صفحة 3:")
                assertArabicText(scanned)
                assertArabicText(mixed)
            }
        } finally { file.delete() }
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
