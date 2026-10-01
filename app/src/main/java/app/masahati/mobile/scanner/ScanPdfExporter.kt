package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.DeflaterOutputStream

/** Lossless RGB, PDF 1.4 with classic cross-reference offsets.
 * One decoded page and one compressed temporary stream at a time. PDFBox is
 * still used elsewhere in the app, but cannot rewrite this scanner export.
 * Validate the platform reader and all source-readable codes before publishing.
 */
object ScanPdfExporter {
    private data class PageInfo(val width: Int,val height: Int,val codes: Set<String>)
    fun export(context: Context,store: ScanSessionStore,target: File) {
        val pages=store.visiblePages
        require(pages.isNotEmpty() && pages.all { it.ready && !it.review })
        target.parentFile?.mkdirs()
        val temporary=File(target.parentFile,target.name+".partial")
        try {
            val info=ArrayList<PageInfo>()
            RandomAccessFile(temporary,"rw").use { sink ->
                sink.setLength(0)
                fun write(value: String) { sink.write(value.toByteArray(Charsets.US_ASCII)) }
                fun number(value: Double)=String.format(Locale.ROOT,"%.4f",value)
                val count=2+pages.size*3;val offsets=LongArray(count+1)
                fun objectStart(id: Int) { offsets[id]=sink.filePointer;write(id.toString()+" 0 obj\n") }
                write("%PDF-1.4\n");sink.write(byteArrayOf(37,0xe2.toByte(),0xe3.toByte(),0xcf.toByte(),0xd3.toByte(),10))
                objectStart(1);write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
                objectStart(2);write("<< /Type /Pages /Count "+pages.size+" /Kids [")
                for(i in pages.indices) write((3+i*3).toString()+" 0 R ")
                write("] >>\nendobj\n")
                for((i,page) in pages.withIndex()) {
                    if(Thread.currentThread().isInterrupted) throw InterruptedException()
                    store.verifySource(page);val file=store.processed(page)
                    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    BitmapFactory.decodeFile(file.absolutePath,bounds)
                    require(bounds.outWidth>0 && bounds.outHeight>0 &&
                        bounds.outWidth.toLong()*bounds.outHeight<=7_000_000) { "أبعاد الصفحة أكبر من الحد الآمن لتصدير PDF" }
                    val image=BitmapFactory.decodeFile(file.absolutePath) ?: error("تعذر فتح الصفحة")
                    val compressed=File.createTempFile("scanner-rgb-",".zlib",context.cacheDir)
                    try {
                        val w=image.width;val h=image.height
                        info.add(PageInfo(w,h,ScanQualityGuard.barcodes(image)))
                        DeflaterOutputStream(compressed.outputStream().buffered()).use { output ->
                            val row=IntArray(w);val rgb=ByteArray(w*3)
                            for(y in 0 until h) {
                                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                                image.getPixels(row,0,w,0,y,w,1)
                                for(x in 0 until w) {
                                    val pixel=row[x];val a=Color.alpha(pixel)
                                    // Alpha composites onto white paper; processed PNG is normally opaque.
                                    rgb[x*3]=((Color.red(pixel)*a+255*(255-a)+127)/255).toByte()
                                    rgb[x*3+1]=((Color.green(pixel)*a+255*(255-a)+127)/255).toByte()
                                    rgb[x*3+2]=((Color.blue(pixel)*a+255*(255-a)+127)/255).toByte()
                                }
                                output.write(rgb)
                            }
                        }
                        val pageId=3+i*3;val contentId=pageId+1;val imageId=pageId+2
                        val pw=number(w*72.0/200);val ph=number(h*72.0/200)
                        objectStart(pageId)
                        write("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+pw+" "+ph+"] "+
                            "/Resources << /XObject << /I "+imageId+" 0 R >> >> /Contents "+contentId+" 0 R >>\nendobj\n")
                        val commands="q\n"+pw+" 0 0 "+ph+" 0 0 cm\n/I Do\nQ\n"
                        objectStart(contentId);write("<< /Length "+commands.toByteArray(Charsets.US_ASCII).size+" >>\nstream\n")
                        write(commands);write("\nendstream\nendobj\n")
                        objectStart(imageId)
                        write("<< /Type /XObject /Subtype /Image /Width "+w+" /Height "+h+
                            " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length "+compressed.length()+" >>\nstream\n")
                        compressed.inputStream().use { input ->
                            val buffer=ByteArray(65536)
                            while(true) { val read=input.read(buffer);if(read<0) break;sink.write(buffer,0,read) }
                        }
                        write("\nendstream\nendobj\n")
                    } finally { image.recycle();compressed.delete() }
                }
                val start=sink.filePointer;write("xref\n0 "+(count+1)+"\n0000000000 65535 f \n")
                for(id in 1..count) write(String.format(Locale.ROOT,"%010d 00000 n \n",offsets[id]))
                write("trailer\n<< /Size "+(count+1)+" /Root 1 0 R >>\nstartxref\n"+start+"\n%%EOF\n")
                sink.fd.sync()
            }
            validate(temporary,info)
            check(temporary.renameTo(target)) { "تعذر حفظ PDF" };store.bindDocument(target)
        } finally { temporary.delete() }
    }
    private fun validate(file: File,pages: List<PageInfo>) {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { reader ->
                check(reader.pageCount==pages.size) { "عدد صفحات PDF غير مطابق" }
                for((i,info) in pages.withIndex()) {
                    if(info.codes.isEmpty()) continue
                    reader.openPage(i).use { page ->
                        val rendered=Bitmap.createBitmap(info.width,info.height,Bitmap.Config.ARGB_8888)
                        try {
                            rendered.eraseColor(Color.WHITE)
                            page.render(rendered,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            check(ScanQualityGuard.barcodes(rendered).containsAll(info.codes)) { "PDF أضعف قراءة رمز؛ لم نحفظ النتيجة" }
                        } finally { rendered.recycle() }
                    }
                }
            }
        }
    }
}
