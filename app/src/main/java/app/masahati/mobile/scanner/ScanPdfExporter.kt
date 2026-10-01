package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object ScanPdfExporter {
    /** Guarded lossless PNGs, temp-file PDF backing, preserved page aspect ratio.
     * Validate the actual platform reader before binding a saved document.
     * If an older PDFium rejects the optimized PNG PDF, use Android's native
     * single-page encoder and deep-copy its resources into a temp-backed merger.
     * Processing one bitmap/page at a time avoids retaining a whole scan in RAM.
     */
    fun export(context: Context,store: ScanSessionStore,target: File) {
        require(store.visiblePages.isNotEmpty() && store.visiblePages.all { it.ready && !it.review })
        PDFBoxResourceLoader.init(context.applicationContext)
        val temporary=File(target.parentFile,target.name+".partial")
        try {
            writePng(context,store,temporary)
            try { validate(temporary,store.visiblePages.size) }
            catch(_: IOException) {
                temporary.delete();writeNativePages(context,store,temporary);validate(temporary,store.visiblePages.size)
            }
            check(temporary.renameTo(target));store.bindDocument(target)
        } finally { temporary.delete() }
    }
    private fun writePng(context: Context,store: ScanSessionStore,target: File) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
            for(page in store.visiblePages) {
                store.verifySource(page);val imageFile=store.processed(page);require(imageFile.isFile)
                val image=PDImageXObject.createFromFileByContent(imageFile,pdf)
                val w=image.width*72f/200f;val h=image.height*72f/200f
                val sheet=PDPage(PDRectangle(w,h));pdf.addPage(sheet)
                PDPageContentStream(pdf,sheet).use { it.drawImage(image,0f,0f,w,h) }
            }
            pdf.documentInformation.title="مسح مستند • مساحاتي";pdf.save(target)
        }
    }
    private fun writeNativePages(context: Context,store: ScanSessionStore,target: File) {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { combined ->
            val merger=PDFMergerUtility()
            for(page in store.visiblePages) {
                store.verifySource(page)
                val image=BitmapFactory.decodeFile(store.processed(page).absolutePath) ?: error("تعذر فتح الصفحة")
                val part=File.createTempFile("scanner-native-",".pdf",context.cacheDir)
                try {
                    val w=(image.width*72.0/200).roundToInt().coerceAtLeast(1)
                    val h=(image.height*72.0/200).roundToInt().coerceAtLeast(1)
                    val native=PdfDocument()
                    try {
                        val sheet=native.startPage(PdfDocument.PageInfo.Builder(w,h,1).create())
                        try { sheet.canvas.drawBitmap(image,null,RectF(0f,0f,w.toFloat(),h.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG)) }
                        finally { native.finishPage(sheet) }
                        part.outputStream().use { native.writeTo(it) }
                    } finally { native.close() }
                    PDDocument.load(part,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { single ->
                        merger.appendDocument(combined,single)
                    }
                    page.report.put("pdf_native_compatibility_fallback",true)
                } finally { image.recycle();part.delete() }
            }
            combined.save(target)
        }
    }
    private fun validate(file: File,count: Int) {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { reader -> check(reader.pageCount==count) { "عدد صفحات PDF غير مطابق" } }
        }
    }
}
