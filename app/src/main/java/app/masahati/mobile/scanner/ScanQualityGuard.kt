package app.masahati.mobile.scanner

import android.graphics.Bitmap
import android.graphics.Color
import app.masahati.mobile.OpenCvDocumentRectifier
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.text.Normalizer
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class ScanGuardDecision(val accepted: Boolean,val reasons: List<String>,val lostBarcodes: Set<String>,val numericChanges: Int)

object ScanGuardPolicy {
    fun evaluate(before: List<Pair<String,Double>>,after: List<Pair<String,Double>>,
        barcodesBefore: Set<String>,barcodesAfter: Set<String>): ScanGuardDecision {
        val lost=barcodesBefore-barcodesAfter;val reasons=mutableListOf<String>();var changed=0
        if(lost.isNotEmpty()) reasons.add("أصبحت قراءة QR أو Barcode أضعف")
        val originals=before.filter { it.second>=.88 }
        val candidates=after.filter { it.second>=.65 }
        val originalText=originals.map { canonical(it.first) }
        val candidateText=candidates.map { canonical(it.first) }
        val assignments=IntArray(originals.size) { -1 };val used=BooleanArray(candidates.size)
        // Match complete lines one-to-one, exact matches first. A reference that
        // survives elsewhere must not hide a changed/removed copy of that number.
        for(i in originals.indices) {
            val match=candidates.indices.firstOrNull { !used[it] && candidateText[it]==originalText[i] }
            if(match!=null) { assignments[i]=match;used[match]=true }
        }
        val pairs=originals.indices.filter { assignments[it]<0 }.flatMap { i ->
            candidates.indices.filter { !used[it] }.map { j ->
                Triple(i,j,editDistance(originalText[i],candidateText[j]).toDouble()/max(1,originalText[i].length)) }
        }.sortedBy { it.third }
        for((i,j,_) in pairs) if(assignments[i]<0 && !used[j]) { assignments[i]=j;used[j]=true }
        for(i in originals.indices) {
            val j=assignments[i];val remaining=if(j<0) mutableListOf() else numbers(candidates[j].first).toMutableList()
            changed+=numbers(originals[i].first).count { !remaining.remove(it) }
            if(j>=0 && candidates[j].second<originals[i].second*.75)
                reasons.add("انخفض وضوح قراءة نص كان واضحًا في الأصل")
            if(originalText[i].length>=8 && (j<0 ||
                    editDistance(originalText[i],candidateText[j]).toDouble()/originalText[i].length>.12))
                reasons.add("تغيرت قراءة سطر واضح؛ يلزم تنظيف أخف")
        }
        if(changed>0) reasons.add("ظهرت قراءة مختلفة للأرقام بعد المعالجة")
        return ScanGuardDecision(reasons.isEmpty(),reasons.distinct(),lost,changed)
    }
    fun numbers(text: String): List<String> = Regex("[\\p{N}][\\p{N}.,:/-]{2,}").findAll(text).map {
        it.value.filter { c -> c.isDigit() }.map { c -> Character.getNumericValue(c) }.joinToString("") }.filter { it.length>=3 }.toList()
    fun canonical(text: String)=Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
    private fun editDistance(a: String,b: String): Int {
        if(abs(a.length-b.length)>max(5,a.length/4)) return max(a.length,b.length)
        var previous=IntArray(b.length+1) { it };var row=IntArray(b.length+1)
        for(i in a.indices) { row[0]=i+1;for(j in b.indices) row[j+1]=minOf(row[j]+1,previous[j+1]+1,previous[j]+if(a[i]==b[j]) 0 else 1)
            val swap=previous;previous=row;row=swap };return previous.last()
    }
}

object ScanQualityGuard {
    /** Full-resolution luma rows avoid allocating a full ARGB pixel array. */
    fun barcodes(bitmap: Bitmap): Set<String> {
        val data=ByteArray(bitmap.width*bitmap.height);val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            for(x in row.indices) { val p=row[x];data[y*bitmap.width+x]=((Color.red(p)*306+Color.green(p)*601+Color.blue(p)*117)/1024).toByte() }
        }
        val source=PlanarYUVLuminanceSource(data,bitmap.width,bitmap.height,0,0,bitmap.width,bitmap.height,false)
        val binary=BinaryBitmap(HybridBinarizer(source))
        val reader=MultiFormatReader();val hints=mapOf(DecodeHintType.TRY_HARDER to true)
        return try { GenericMultipleBarcodeReader(reader).decodeMultiple(binary,hints).map { it.text }.toSet() }
            catch(_: NotFoundException) { emptySet() } finally { reader.reset() }
    }
    /** Measure local foreground contrast and chroma preservation, not semantic claims about
     * signatures. Comparing OCR alone cannot prove a stamp or punctuation dot survived.
     */
    fun detailReasons(before: Bitmap,after: Bitmap,mode: ScanFilter): List<String> {
        require(before.width==after.width && before.height==after.height)
        val w=before.width;val h=before.height;val step=max(1,max(w,h)/1400)
        val b=IntArray(w);var colored=0;var keptColors=0;var marks=0;var keptMarks=0
        fun lum(p: Int)=(Color.red(p)*306+Color.green(p)*601+Color.blue(p)*117)/1024
        fun spread(p: Int)=maxOf(Color.red(p),Color.green(p),Color.blue(p))-minOf(Color.red(p),Color.green(p),Color.blue(p))
        check(OpenCvDocumentRectifier.isAvailable())
        // A one-sided dark-to-bright transition may be a shadow, not a glyph.
        // Estimate the original local paper with a source-resolution closing:
        // thin dark strokes are filled in the GUIDE ONLY, whereas a broad
        // illumination step keeps its level on each side. The actual image and
        // all color checks stay unchanged. Rectangular closing is separable;
        // row tiles plus its complete 30-pixel dependency halo bound the RAM.
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(31.0,31.0))
        try {
            for(start in 0 until h step 128) {
                if(Thread.currentThread().isInterrupted) throw InterruptedException()
                val top=max(0,start-30);val bottom=min(h,start+128+30);val rows=bottom-top
                val original=IntArray(w*rows);before.getPixels(original,0,w,0,top,w,rows)
                val source=Mat(rows,w,CvType.CV_8U);val background=Mat()
                try {
                    val luminance=ByteArray(original.size) { lum(original[it]).toByte() }
                    source.put(0,0,luminance);Imgproc.morphologyEx(source,background,Imgproc.MORPH_CLOSE,kernel)
                    val guide=ByteArray(original.size);background.get(0,0,guide)
                    val first=max(step,start).let { ((it+step-1)/step)*step }
                    for(y in first until min(h-step,start+128) step step) {
                        val offset=(y-top)*w;after.getPixels(b,0,w,0,y,w,1)
                        for(x in step until w-step step step) {
                            val p=original[offset+x];val value=lum(p)
                            val neighbor=max(lum(original[offset+x-step]),lum(original[offset+x+step]))
                            val paper=guide[offset+x].toInt() and 255
                            if(neighbor-value>=12 && value<neighbor*.85 && paper-value>=12 && value<paper*.85) {
                                marks++;val changed=lum(b[x]);val changedNeighbor=max(lum(b[x-step]),lum(b[x+step]))
                                if(changedNeighbor-changed>=max(6.0,(neighbor-value)*.3)) keptMarks++
                            }
                            if(mode!=ScanFilter.BLACK_WHITE && spread(p)>55 && value<220) {
                                colored++
                                val r=Color.red(p)-Color.green(p);val bb=Color.blue(p)-Color.green(p)
                                val rr=Color.red(b[x])-Color.green(b[x]);val bbb=Color.blue(b[x])-Color.green(b[x])
                                val angle=abs(atan2(bb.toDouble(),r.toDouble())-atan2(bbb.toDouble(),rr.toDouble()))
                                if(spread(b[x])>=spread(p)*.45 && (angle<.5 || angle>2*PI-.5)) keptColors++
                            }
                        }
                    }
                } finally { source.release();background.release() }
            }
        } finally { kernel.release() }
        return buildList {
            if(marks>=50 && keptMarks.toDouble()/marks<.94) add("انخفضت تفاصيل الحروف والعلامات الصغيرة")
            if(colored>=25 && keptColors.toDouble()/colored<.94) add("ضعفت بعض الألوان المهمة")
        }
    }
}
