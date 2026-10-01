package app.masahati.mobile.scanner

import java.util.Random
import kotlin.math.*

/** Fractions of the upright original, independent from inference letterboxing. */
data class ScanPoint(val x: Double,val y: Double) {
    fun distance(p: ScanPoint)=hypot(x-p.x,y-p.y)
    fun finite()=x.isFinite() && y.isFinite()
}
data class DocumentQuad(val points: List<ScanPoint>,val confidence: Double=0.0,
    val cornerConfidence: List<Double> = List(4){confidence},val origin: String="manual") {
    fun valid()=ScanGeometry.valid(points)
    fun area()=ScanGeometry.area(points)
    fun fullyVisible(inset: Double=0.008)=valid() && points.all { it.x in inset..1-inset && it.y in inset..1-inset }
    companion object {
        fun inset(margin: Double=0.04)=DocumentQuad(listOf(ScanPoint(margin,margin),ScanPoint(1-margin,margin),
            ScanPoint(1-margin,1-margin),ScanPoint(margin,1-margin)))
    }
}
data class EdgeSample(val point: ScanPoint,val weight: Double=1.0)
data class ProfileEdgeSample(val point: ScanPoint,val profile: Int,val strength: Double,val transitionWidthPx: Double=0.0)
data class FittedLine(val nx: Double,val ny: Double,val c: Double,val inlierFraction: Double=0.0,val residualPx: Double=0.0,
    val transitionWidthPx: Double=0.0) {
    fun distance(p: ScanPoint)=abs(nx*p.x+ny*p.y+c)
}
object ScanGeometry {
    fun area(p: List<ScanPoint>): Double = if(p.size!=4) 0.0 else abs(p.indices.sumOf {
        p[it].x*p[(it+1)%4].y-p[it].y*p[(it+1)%4].x })/2
    fun valid(p: List<ScanPoint>): Boolean {
        if(p.size!=4 || p.any { !it.finite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return false
        return area(p) in 0.008..1.000001 && p.indices.all {
            val a=p[it]; val b=p[(it+1)%4]; val c=p[(it+2)%4]
            (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)>1e-7 && a.distance(b)>0.012 }
    }
    fun intersection(a: FittedLine,b: FittedLine): ScanPoint? {
        val d=a.nx*b.ny-b.nx*a.ny
        if(abs(d)<1e-6) return null
        return ScanPoint((a.ny*b.c-b.ny*a.c)/d,(b.nx*a.c-a.nx*b.c)/d).takeIf { it.finite() }
    }
    fun lineThrough(a: ScanPoint,b: ScanPoint): FittedLine? {
        val d=a.distance(b); if(d<1e-8) return null
        val nx=-(b.y-a.y)/d; val ny=(b.x-a.x)/d
        return FittedLine(nx,ny,-(nx*a.x+ny*a.y))
    }
    /** Width of the observed gradient ridge, in ORIGINAL source pixels.
     * A low line-fit residual does not make a broad motion/defocus transition
     * precisely localized. Capping the search bounds work without hiding blur.
     */
    fun halfMaximumWidth(peak: Int,height: Double,response: (Int)->Double): Double {
        if(!height.isFinite() || height<=0) return 49.0
        val half=height*.5;var left=0;var right=0
        while(left<24 && response(peak-left-1)>=half) left++
        while(right<24 && response(peak+right+1)>=half) right++
        return (left+right+1).toDouble()
    }
    fun robustLine(samples: List<EdgeSample>,tolerancePx: Double=2.5): FittedLine? {
        val clean=samples.filter { it.point.finite() && it.weight.isFinite() && it.weight>0 }
        if(clean.size<8) return null
        val random=Random(73071446); var chosen: List<EdgeSample> = emptyList(); var score=0.0
        repeat(96) {
            val a=clean[random.nextInt(clean.size)].point; val b=clean[random.nextInt(clean.size)].point
            if(a.distance(b)<12) return@repeat
            val line=lineThrough(a,b) ?: return@repeat
            val inliers=clean.filter { line.distance(it.point)<=tolerancePx }
            val value=inliers.sumOf { it.weight.coerceIn(0.2,2.0) }
            if(value>score) { score=value; chosen=inliers }
        }
        if(chosen.size<max(8,(clean.size*0.55).toInt())) return null
        val weight=chosen.sumOf { it.weight.coerceIn(0.2,2.0) }
        val cx=chosen.sumOf { it.point.x*it.weight.coerceIn(0.2,2.0) }/weight
        val cy=chosen.sumOf { it.point.y*it.weight.coerceIn(0.2,2.0) }/weight
        var xx=0.0;var yy=0.0;var xy=0.0
        chosen.forEach { val x=it.point.x-cx;val y=it.point.y-cy;val w=it.weight.coerceIn(0.2,2.0)
            xx+=w*x*x;yy+=w*y*y;xy+=w*x*y }
        if(xx+yy<1e-5) return null
        val angle=0.5*atan2(2*xy,xx-yy);val nx=-sin(angle);val ny=cos(angle)
        val line=FittedLine(nx,ny,-(nx*cx+ny*cy),chosen.size.toDouble()/clean.size)
        return line.copy(residualPx=sqrt(chosen.sumOf { line.distance(it.point).pow(2) }/chosen.size))
    }
    /** Several transitions per normal profile, but only ONE vote per profile.
     * Printed texture can produce many stronger peaks than a weak paper edge.
     * Select a spatially coherent boundary with a localization prior, then TLS.
     */
    fun robustProfileLine(samples: List<ProfileEdgeSample>,profileCount: Int,reference: FittedLine,
        midpoint: ScanPoint,radius: Double): FittedLine? {
        val clean=samples.filter { it.point.finite() && it.profile in 0 until profileCount && it.strength.isFinite() && it.strength>0 }
        if(clean.size<40 || profileCount<40 || radius<=0) return null
        val random=Random(73071446);var best=IntArray(0);var bestScore=-1.0
        val closest=IntArray(profileCount);val distances=DoubleArray(profileCount)
        repeat(192) {
            val a=clean[random.nextInt(clean.size)].point;val b=clean[random.nextInt(clean.size)].point
            if(a.distance(b)<40) return@repeat
            val line=lineThrough(a,b) ?: return@repeat
            if(abs(line.nx*reference.nx+line.ny*reference.ny)<cos(7.5*PI/180)) return@repeat
            closest.fill(-1);distances.fill(Double.POSITIVE_INFINITY)
            clean.forEachIndexed { index,sample ->
                val distance=line.distance(sample.point)
                if(distance<=2.5 && distance<distances[sample.profile]) {
                    closest[sample.profile]=index;distances[sample.profile]=distance
                }
            }
            val count=closest.count { it>=0 }
            if(count<40) return@repeat
            val score=count*exp(-.5*(line.distance(midpoint)/(radius*.65)).pow(2))
            if(score>bestScore) { bestScore=score;best=closest.filter { it>=0 }.toIntArray() }
        }
        if(best.size<ceil(profileCount*.85).toInt()) return null
        val chosen=best.map { clean[it] };val weights=chosen.map { (it.strength/70).coerceIn(.4,1.5) }
        val total=weights.sum()
        val cx=chosen.indices.sumOf { chosen[it].point.x*weights[it] }/total
        val cy=chosen.indices.sumOf { chosen[it].point.y*weights[it] }/total
        var xx=0.0;var yy=0.0;var xy=0.0
        chosen.indices.forEach { i -> val x=chosen[i].point.x-cx;val y=chosen[i].point.y-cy
            xx+=weights[i]*x*x;yy+=weights[i]*y*y;xy+=weights[i]*x*y }
        if(xx+yy<1e-5) return null
        val angle=.5*atan2(2*xy,xx-yy);val nx=-sin(angle);val ny=cos(angle)
        val widths=chosen.map { it.transitionWidthPx }.filter { it.isFinite() && it>0 }.sorted()
        val width=if(widths.isEmpty()) 0.0 else (widths[(widths.size-1)/2]+widths[widths.size/2])/2
        val fitted=FittedLine(nx,ny,-nx*cx-ny*cy,chosen.size.toDouble()/profileCount,transitionWidthPx=width)
        val residual=sqrt(chosen.sumOf { fitted.distance(it.point).pow(2) }/chosen.size)
        return fitted.copy(residualPx=residual).takeIf { residual<=2.7 &&
            abs(it.nx*reference.nx+it.ny*reference.ny)>=cos(7.5*PI/180) && it.distance(midpoint)<=radius*1.15 }
    }
    /** Move each edge OUTWARD in source pixels. Clamp at image borders; never shrink. */
    fun padded(p: List<ScanPoint>,width: Int,height: Int,paddingPx: Double): List<ScanPoint> {
        if(!valid(p) || width<2 || height<2) return p
        val pixels=p.map { ScanPoint(it.x*(width-1),it.y*(height-1)) }
        val center=ScanPoint(pixels.sumOf { it.x }/4,pixels.sumOf { it.y }/4)
        val lines=pixels.indices.map { i -> val line=lineThrough(pixels[i],pixels[(i+1)%4])!!
            val sign=if(line.nx*center.x+line.ny*center.y+line.c>0) 1 else -1
            line.copy(c=line.c+sign*paddingPx.coerceIn(0.0,24.0)) }
        val result=pixels.indices.map { i -> val v=intersection(lines[(i+3)%4],lines[i]) ?: pixels[i]
            ScanPoint((v.x/(width-1)).coerceIn(0.0,1.0),(v.y/(height-1)).coerceIn(0.0,1.0)) }
        return if(valid(result)) result else p
    }
    fun outputSize(p: List<ScanPoint>,width: Int,height: Int,maxSide: Int): Pair<Int,Int> {
        require(valid(p));val v=p.map { ScanPoint(it.x*(width-1),it.y*(height-1)) }
        val w=max(v[0].distance(v[1]),v[3].distance(v[2]));val h=max(v[0].distance(v[3]),v[1].distance(v[2]))
        val scale=min(1.0,maxSide/max(w,h))
        return max(32,(w*scale).roundToInt()) to max(32,(h*scale).roundToInt())
    }
}
