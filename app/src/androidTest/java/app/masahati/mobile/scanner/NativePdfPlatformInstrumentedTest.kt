package app.masahati.mobile.scanner

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Independent platform diagnostic: no OpenCV, ORT, OCR or PDFBox writer. */
@RunWith(AndroidJUnit4::class)
class NativePdfPlatformInstrumentedTest {
    @Test fun nativeWriterReaderControl() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"native-pdf-control.pdf")
        val writer=PdfDocument()
        try {
            val page=writer.startPage(PdfDocument.PageInfo.Builder(210,297,1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("73071446",20f,60f,Paint().apply { color=Color.BLACK;textSize=22f })
            writer.finishPage(page);file.outputStream().use(writer::writeTo)
        } finally { writer.close() }
        assertTrue(file.length()>100)
        try {
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf -> assertTrue(pdf.pageCount==1) }
            }
            Log.i("ScannerPdfPlatform","NATIVE_CONTROL_OK api="+android.os.Build.VERSION.SDK_INT)
        } catch(error: Exception) {
            Log.e("ScannerPdfPlatform","NATIVE_CONTROL_FAILED api="+android.os.Build.VERSION.SDK_INT+" "+error.javaClass.name+": "+error.message)
            // Synthetic test only. Retain independent bytes for a host reader.
            val encoded=Base64.encodeToString(file.readBytes(),Base64.NO_WRAP)
            encoded.chunked(2800).forEachIndexed { i,part -> Log.i("ScannerPdfPlatform","CONTROL_BASE64_"+i+"="+part) }
        } finally { file.delete() }
    }
}
