package app.masahati.mobile.scanner

import android.graphics.Bitmap
import androidx.core.graphics.scale
import app.masahati.mobile.OpenCvDocumentRectifier
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class PageGeometryEvidence(val deskewDegrees: Double,val curved: Boolean,val curveConfidence: Double,
    val curvedLines: Int,val bendPixels: Double)

object ScanPageGeometry {
    /** Analysis only. Text pixels are not reconstructed; final deskew is composed with
     * the source homography so native text is interpolated only once on flat pages.
     */
    fun analyze(source: Bitmap): PageGeometryEvidence {
        check(OpenCvDocumentRectifier.isAvailable())
        val scale=min(1.0,1100.0/max(source.width,source.height))
        val thumb=source.scale(max(32,(source.width*scale).toInt()),max(32,(source.height*scale).toInt()))
        val rgba=Mat();val gray=Mat();val mask=Mat();val linked=Mat();val lines=Mat();val hierarchy=Mat()
        val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,Size(max(9.0,thumb.width/50.0),1.0))
        val contours=ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(thumb,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.adaptiveThreshold(gray,mask,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY_INV,31,15.0)
            Imgproc.dilate(mask,linked,kernel)
            Imgproc.HoughLinesP(linked,lines,1.0,PI/1800,80,thumb.width*.28,15.0)
            val angles=ArrayList<Double>()
            for(i in 0 until lines.rows()) { val line=lines.get(i,0);if(line.size<4) continue
                val angle=atan2(line[3]-line[1],line[2]-line[0])*180/PI
                if(abs(angle)<=3) angles.add(angle) }
            val sorted=angles.sorted();val median=if(sorted.size>=5) sorted[sorted.size/2] else 0.0
            val consensus=angles.count { abs(it-median)<.4 }.toDouble()/angles.size.coerceAtLeast(1)
            val deskew=if(consensus>=.7 && abs(median) in .12..2.2) -median else 0.0
            Imgproc.findContours(linked,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val ink=ByteArray(thumb.width*thumb.height);mask.get(0,0,ink)
            val bends=ArrayList<Double>();var tested=0
            for(contour in contours) {
                val rect=Imgproc.boundingRect(contour)
                if(rect.width<thumb.width*.30 || rect.height<6 || rect.height>thumb.height*.055) continue
                val points=ArrayList<Pair<Double,Double>>()
                for(part in 0 until 12) {
                    val x0=rect.x+part*rect.width/12;val x1=rect.x+(part+1)*rect.width/12
                    var sum=0.0;var count=0
                    for(y in rect.y until rect.y+rect.height) for(x in x0 until x1)
                        if((ink[y*thumb.width+x].toInt() and 255)>0) { sum+=y;count++ }
                    if(count>=12) points.add((part/11.0*2-1) to sum/count)
                }
                if(points.size<9) continue
                tested++
                val fit=quadratic(points) ?: continue
                val linearSlope=points.sumOf { it.first*(it.second-points.sumOf { p -> p.second }/points.size) }/points.sumOf { it.first*it.first }
                val average=points.sumOf { it.second }/points.size
                val linearError=points.sumOf { (it.second-average-linearSlope*it.first).pow(2) }
                val curveError=points.sumOf { (it.second-fit[0]-fit[1]*it.first-fit[2]*it.first*it.first).pow(2) }
                if(abs(fit[2])>max(3.0,thumb.width*.0045) && curveError<linearError*.68) bends.add(fit[2])
            }
            val positive=bends.count { it>0 };val negative=bends.size-positive
            val agreeing=max(positive,negative);val confident=agreeing>=3 && agreeing.toDouble()/tested.coerceAtLeast(1)>=.35
            val confidence=if(confident) min(1.0,agreeing/5.0) else 0.0
            val bend=if(bends.isEmpty()) 0.0 else bends.map { abs(it) }.sorted()[bends.size/2]
            return PageGeometryEvidence(deskew,confident,confidence,bends.size,bend/scale)
        } finally { if(thumb!==source) thumb.recycle();listOf(rgba,gray,mask,linked,lines,hierarchy,kernel).forEach { it.release() };contours.forEach { it.release() } }
    }
    private fun quadratic(points: List<Pair<Double,Double>>): DoubleArray? {
        val a=Array(3) { DoubleArray(4) }
        for((x,y) in points) { val row=doubleArrayOf(1.0,x,x*x)
            for(i in 0..2) { for(j in 0..2) a[i][j]+=row[i]*row[j];a[i][3]+=row[i]*y } }
        for(i in 0..2) {
            val best=(i..2).maxBy { abs(a[it][i]) };val swap=a[i];a[i]=a[best];a[best]=swap
            if(abs(a[i][i])<1e-9) return null
            val d=a[i][i];for(j in i..3) a[i][j]/=d
            for(k in 0..2) if(k!=i) { val factor=a[k][i];for(j in i..3) a[k][j]-=factor*a[i][j] }
        }
        return DoubleArray(3) { a[it][3] }
    }
}
