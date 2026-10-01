package app.masahati.mobile.scanner

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

data class CaptureQuality(val sharpness: Double,val darkFraction: Double,val clippedFraction: Double,
    val glareFraction: Double,val shadowSeverity: Double,val focused: Boolean=true) {
    fun acceptable()=sharpness>=42 && darkFraction<.32 && clippedFraction<.035 && glareFraction<.018 && focused
    fun guidance()=when {
        !focused || sharpness<42 -> "ثبّت الهاتف وانتظر وضوح النص"
        darkFraction>=.32 -> "الإضاءة ضعيفة؛ زد إضاءة الورقة"
        clippedFraction>=.035 || glareFraction>=.018 -> "انعكاس قوي؛ غيّر زاوية الإضاءة"
        shadowSeverity>.45 -> "يوجد ظل؛ حاول إبعاده عن الورقة"
        else -> "الوضوح والإضاءة مناسبان" }
}
object ScanQuality {
    /** Bounded preview analysis inside the paper, no full-resolution bitmap allocation. */
    fun analyze(bitmap: Bitmap,quad: DocumentQuad?,focused: Boolean=true): CaptureQuality {
        val w=bitmap.width;val h=bitmap.height;val step=max(1,max(w,h)/520)
        val row=IntArray(w);val previous=IntArray(w);val next=IntArray(w)
        var count=0;var dark=0;var clipped=0;var glare=0;var lapSum=0.0;var lapSquared=0.0
        val tileHist=Array(64) { IntArray(32) };val tileCounts=IntArray(64)
        fun luminance(p: Int)=.299*Color.red(p)+.587*Color.green(p)+.114*Color.blue(p)
        fun inPaper(x: Int,y: Int): Boolean {
            val p=quad?.points ?: return x>w*.08 && x<w*.92 && y>h*.08 && y<h*.92
            val xx=x.toDouble()/w;val yy=y.toDouble()/h
            return p.indices.all { i -> val a=p[i];val b=p[(i+1)%4]
                (b.x-a.x)*(yy-a.y)-(b.y-a.y)*(xx-a.x)>=0 }
        }
        for(y in step until h-step step step) {
            bitmap.getPixels(row,0,w,0,y,w,1);bitmap.getPixels(previous,0,w,0,y-step,w,1);bitmap.getPixels(next,0,w,0,y+step,w,1)
            for(x in step until w-step step step) if(inPaper(x,y)) {
                val v=luminance(row[x]);val left=luminance(row[x-step]);val right=luminance(row[x+step]);val above=luminance(previous[x]);val below=luminance(next[x])
                val lap=4*v-left-right-above-below
                lapSum+=lap;lapSquared+=lap*lap;count++
                if(v<35) dark++
                // Ink edges on ordinary white paper must not block the shutter.
                // A highlight proxy requires bright, similar-valued neighbours;
                // saturated peaks beside dark text are content, not glare.
                val darkest=min(min(left,right),min(above,below));val brightest=max(max(left,right),max(above,below))
                val brightNeighbourhood=darkest>=210 && brightest-darkest<24
                if(brightNeighbourhood && v>254 && abs(lap)>12) clipped++
                if(brightNeighbourhood && v>251 && abs(lap)>35) glare++
                val tile=min(7,y*8/h)*8+min(7,x*8/w);tileHist[tile][(v/8).toInt().coerceIn(0,31)]++;tileCounts[tile]++
            }
        }
        val backgrounds=tileHist.indices.filter { tileCounts[it]>25 }.map { i ->
            var sum=0;var v=31
            for(b in 0..31) { sum+=tileHist[i][b];if(sum>=tileCounts[i]*.88) { v=b;break } };v*8.0+4 }.sorted()
        val spread=if(backgrounds.size>8) backgrounds[(backgrounds.lastIndex*.9).toInt()]-backgrounds[(backgrounds.lastIndex*.1).toInt()] else 0.0
        val n=count.coerceAtLeast(1).toDouble()
        return CaptureQuality(max(0.0,lapSquared/n-(lapSum/n).pow(2)),dark/n,clipped/n,glare/n,(spread/130).coerceIn(0.0,1.0),focused)
    }
}

/** Auto shutter is a state machine: complete, confident, sharp, stable corners over
 * >= 8 observations AND >= 900 ms. A bad frame resets the gate immediately.
 */
class StableCaptureGate {
    private var last: DocumentQuad?=null;private var count=0;private var start=0L
    var motion: Double=1.0;private set
    fun reset() { last=null;count=0;start=0L;motion=1.0 }
    fun observe(quad: DocumentQuad?,quality: CaptureQuality,now: Long): Boolean {
        val old=last
        motion=if(quad!=null && old!=null) quad.points.zip(old.points).maxOf { (a,b) -> a.distance(b) } else 1.0
        val valid=quad!=null && quad.fullyVisible() && quad.area()>=.20 && quad.confidence>=.78 &&
            quad.cornerConfidence.all { it>=.80 } && quality.acceptable()
        if(!valid || (old!=null && motion>.0055)) { count=0;start=now }
        if(valid) { if(count==0) start=now;count++ };last=quad
        return valid && count>=8 && now-start>=900
    }
}
