package app.masahati.mobile.scanner

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.text.Normalizer
import kotlin.math.*

data class ScanGuardDecision(val accepted: Boolean,val reasons: List<String>,val lostBarcodes: Set<String>,val numericChanges: Int)

object ScanGuardPolicy {
    fun evaluate(before: List<Pair<String,Double>>,after: List<Pair<String,Double>>,
        barcodesBefore: Set<String>,barcodesAfter: Set<String>): ScanGuardDecision {
        val lost=barcodesBefore-barcodesAfter;val reasons=mutableListOf<String>();var changed=0
        if(lost.isNotEmpty()) reasons.add("أصبحت قراءة QR أو Barcode أضعف")
        val afterNumbers=after.filter { it.second>=.65 }.flatMap { numbers(it.first) }.toSet()
        val afterText=after.filter { it.second>=.65 }.map { canonical(it.first) }
        for((text,confidence) in before.filter { it.second>=.88 }) {
            val missing=numbers(text).count { it !in afterNumbers }
            if(missing>0) changed+=missing
            val original=canonical(text)
            if(original.length>=8 && afterText.none { editDistance(original,it).toDouble()/max(1,original.length)<=.12 })
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
        val a=IntArray(w);val b=IntArray(w);var colored=0;var keptColors=0;var marks=0;var keptMarks=0
        fun lum(p: Int)=(Color.red(p)*306+Color.green(p)*601+Color.blue(p)*117)/1024
        fun spread(p: Int)=maxOf(Color.red(p),Color.green(p),Color.blue(p))-minOf(Color.red(p),Color.green(p),Color.blue(p))
        for(y in step until h-step step step) {
            before.getPixels(a,0,w,0,y,w,1);after.getPixels(b,0,w,0,y,w,1)
            for(x in step until w-step step step) {
                val value=lum(a[x]);val neighbor=max(lum(a[x-step]),lum(a[x+step]))
                if(neighbor-value>=12 && value<neighbor*.85) {
                    marks++;val changed=lum(b[x]);val changedNeighbor=max(lum(b[x-step]),lum(b[x+step]))
                    if(changedNeighbor-changed>=max(6.0,(neighbor-value)*.3)) keptMarks++
                }
                if(mode!=ScanFilter.BLACK_WHITE && spread(a[x])>55 && value<220) {
                    colored++
                    val r=Color.red(a[x])-Color.green(a[x]);val bb=Color.blue(a[x])-Color.green(a[x])
                    val rr=Color.red(b[x])-Color.green(b[x]);val bbb=Color.blue(b[x])-Color.green(b[x])
                    val angle=abs(atan2(bb.toDouble(),r.toDouble())-atan2(bbb.toDouble(),rr.toDouble()))
                    if(spread(b[x])>=spread(a[x])*.45 && (angle<.5 || angle>2*PI-.5)) keptColors++
                }
            }
        }
        return buildList {
            if(marks>=50 && keptMarks.toDouble()/marks<.94) add("انخفضت تفاصيل الحروف والعلامات الصغيرة")
            if(colored>=25 && keptColors.toDouble()/colored<.94) add("ضعفت بعض الألوان المهمة")
        }
    }
}
