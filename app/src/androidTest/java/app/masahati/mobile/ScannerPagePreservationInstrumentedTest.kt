package app.masahati.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScannerPagePreservationInstrumentedTest {
    @Test
    fun printedInnerBorderIsNotMistakenForPageBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.WHITE)
        canvas.drawRoundRect(
            RectF(145f, 180f, 855f, 1220f), 5f, 5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = 9f
                style = Paint.Style.STROKE
            }
        )
        val input = File(context.cacheDir, "scanner-inner-border-${System.nanoTime()}.jpg")
        val output = File(context.cacheDir, "scanner-inner-border-${System.nanoTime()}.pdf")
        try {
            input.outputStream().use { stream ->
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 95, stream))
            }
            var processedPages = 0
            val written = DocumentImageEnhancer.processPagesToPdf(
                context, listOf(Uri.fromFile(input)), output
            ) { index, page ->
                assertEquals(0, index)
                assertEquals(source.width, page.width)
                assertEquals(source.height, page.height)
                processedPages++
            }
            assertEquals(1, written)
            assertEquals(1, processedPages)
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(1, pdf.pageCount)
                    pdf.openPage(0).use { page ->
                        assertEquals(source.width, page.width)
                        assertEquals(source.height, page.height)
                    }
                }
            }
        } finally {
            source.recycle()
            input.delete()
            output.delete()
        }
    }
}
