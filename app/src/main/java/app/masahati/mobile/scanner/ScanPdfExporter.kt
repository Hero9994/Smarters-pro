package app.masahati.mobile.scanner

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

object ScanPdfExporter {
    /** Guarded lossless PNGs, temp-file PDF backing, preserved page aspect ratio. */
    fun export(context: Context,store: ScanSessionStore,target: File) {
        require(store.visiblePages.isNotEmpty() && store.visiblePages.all { it.ready && !it.review })
        PDFBoxResourceLoader.init(context.applicationContext)
        val temporary=File(target.parentFile,target.name+".partial")
        try {
            PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use { pdf ->
                for(page in store.visiblePages) {
                    store.verifySource(page);val imageFile=store.processed(page);require(imageFile.isFile)
                    val image=PDImageXObject.createFromFileByContent(imageFile,pdf)
                    val w=image.width*72f/200f;val h=image.height*72f/200f
                    val sheet=PDPage(PDRectangle(w,h));pdf.addPage(sheet)
                    PDPageContentStream(pdf,sheet).use { it.drawImage(image,0f,0f,w,h) }
                }
                pdf.documentInformation.title="مسح مستند • مساحاتي";pdf.save(temporary)
            }
            check(temporary.renameTo(target));store.bindDocument(target)
        } finally { temporary.delete() }
    }
}
