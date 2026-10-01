package app.masahati.mobile.scanner

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.util.TypedValue
import androidx.core.graphics.withClip
import kotlin.math.*

/** Zoomable crop editor with native-coordinate corners and a pixel magnifier. */
class ScanCornerView(context: Context): View(context) {
    var bitmap: Bitmap?=null;set(value) { field=value;zoom=1f;panX=0f;panY=0f;invalidate() }
    var quad=DocumentQuad.inset();set(value) { field=value;invalidate() }
    var onChanged: ((DocumentQuad)->Unit)?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path();private val rectBuffer=RectF();private val magnifierBox=RectF();private val magnifierMatrix=Matrix()
    private var zoom=1f;private var panX=0f;private var panY=0f;private var active=-1
    private var previousX=0f;private var previousY=0f
    private val density=resources.displayMetrics.density
    private val pinch=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoom=(zoom*detector.scaleFactor).coerceIn(1f,6f);invalidate();return true }
    })
    init { contentDescription="تعديل زوايا الورقة؛ اسحب النقاط وكبّر بإصبعين";setBackgroundColor(Color.rgb(20,25,29)) }
    private fun imageRect(): RectF {
        val b=bitmap ?: return rectBuffer.apply { setEmpty() }
        val scale=min(width.toFloat()/b.width,height.toFloat()/b.height)*.92f*zoom
        val w=b.width*scale;val h=b.height*scale
        return rectBuffer.apply { set((width-w)/2+panX,(height-h)/2+panY,(width+w)/2+panX,(height+h)/2+panY) }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val b=bitmap ?: return;val rect=imageRect()
        paint.reset();paint.isFilterBitmap=true;canvas.drawBitmap(b,null,rect,paint)
        path.reset();quad.points.forEachIndexed { i,p -> val x=rect.left+(p.x*rect.width()).toFloat();val y=rect.top+(p.y*rect.height()).toFloat()
            if(i==0) path.moveTo(x,y) else path.lineTo(x,y) };path.close()
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.5f*density;paint.color=Color.rgb(33,218,181);canvas.drawPath(path,paint)
        quad.points.forEachIndexed { i,p -> val x=rect.left+(p.x*rect.width()).toFloat();val y=rect.top+(p.y*rect.height()).toFloat()
            paint.style=Paint.Style.FILL;paint.color=Color.WHITE;canvas.drawCircle(x,y,11*density,paint)
            paint.color=Color.rgb(12,110,95);canvas.drawCircle(x,y,7*density,paint)
            paint.textSize=13*density;paint.color=Color.WHITE;canvas.drawText("${i+1}",x+15*density,y-13*density,paint) }
        if(active>=0) {
            val p=quad.points[active];val size=124*density
            val box=magnifierBox.apply { set(width-size-12*density,12*density,width-12*density,12*density+size) }
            val scale=3f;val sx=(p.x*b.width).toFloat();val sy=(p.y*b.height).toFloat()
            val matrix=magnifierMatrix;matrix.setScale(scale,scale);matrix.postTranslate(box.centerX()-sx*scale,box.centerY()-sy*scale)
            canvas.withClip(box) {
                drawColor(Color.BLACK);paint.isFilterBitmap=false;drawBitmap(b,matrix,paint)
                paint.color=Color.rgb(33,218,181);paint.strokeWidth=1.5f*density
                drawLine(box.left,box.centerY(),box.right,box.centerY(),paint);drawLine(box.centerX(),box.top,box.centerX(),box.bottom,paint)
            }
            paint.style=Paint.Style.STROKE;canvas.drawRect(box,paint)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if(event.pointerCount>1) { active=-1;invalidate();return true }
        val rect=imageRect();if(rect.width()<=0) return true
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { previousX=event.x;previousY=event.y
                active=quad.points.indices.minByOrNull { i -> val p=quad.points[i]
                    hypot(event.x-rect.left-(p.x*rect.width()).toFloat(),event.y-rect.top-(p.y*rect.height()).toFloat()) } ?: -1
                if(active>=0) { val p=quad.points[active]
                    if(hypot(event.x-rect.left-(p.x*rect.width()).toFloat(),event.y-rect.top-(p.y*rect.height()).toFloat())>42*density) active=-1 }
            }
            MotionEvent.ACTION_MOVE -> {
                if(active>=0 && !pinch.isInProgress) {
                    val points=quad.points.toMutableList();points[active]=ScanPoint(((event.x-rect.left)/rect.width()).toDouble().coerceIn(0.0,1.0),
                        ((event.y-rect.top)/rect.height()).toDouble().coerceIn(0.0,1.0))
                    val next=DocumentQuad(points,1.0,List(4){1.0},"manual")
                    if(next.valid()) { quad=next;onChanged?.invoke(next) }
                } else if(zoom>1) { panX+=event.x-previousX;panY+=event.y-previousY }
                previousX=event.x;previousY=event.y;invalidate()
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { active=-1;invalidate();performClick() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
}

/** Press or drag to compare on identical geometry; raw capture is separately accessible. */
class ScanCompareView(context: Context): View(context) {
    var before: Bitmap?=null;set(value) { field=value;invalidate() }
    var after: Bitmap?=null;set(value) { field=value;invalidate() }
    private var split=.5f;private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect=RectF()
    init { contentDescription="مقارنة قبل وبعد؛ اسحب الخط";setBackgroundColor(Color.rgb(20,25,29)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas);val b=before ?: return
        val scale=min(width.toFloat()/b.width,height.toFloat()/b.height)
        rect.set((width-b.width*scale)/2,(height-b.height*scale)/2,(width+b.width*scale)/2,(height+b.height*scale)/2)
        canvas.drawBitmap(b,null,rect,paint)
        after?.let { a -> canvas.withClip(width*split,0f,width.toFloat(),height.toFloat()) { drawBitmap(a,null,rect,paint) } }
        paint.color=Color.WHITE;paint.strokeWidth=resources.displayMetrics.density*2;canvas.drawLine(width*split,0f,width*split,height.toFloat(),paint)
        paint.textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,16f,resources.displayMetrics);canvas.drawText("قبل",12f,28f,paint);canvas.drawText("بعد",width-55f,28f,paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean { split=(event.x/width.coerceAtLeast(1)).coerceIn(0f,1f);invalidate();if(event.actionMasked==MotionEvent.ACTION_UP) performClick();return true }
    override fun performClick(): Boolean { super.performClick();return true }
}
