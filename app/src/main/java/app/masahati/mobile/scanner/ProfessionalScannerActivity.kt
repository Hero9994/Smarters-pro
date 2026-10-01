package app.masahati.mobile.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.*
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import android.util.Size
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Native scanner screen. Raw captures are durable before detection starts.
 * All pages require crop review; manual zoom corners and the old scanner remain.
 */
@OptIn(ExperimentalCamera2Interop::class,TransformExperimental::class,ExperimentalZeroShutterLag::class)
class ProfessionalScannerActivity: ComponentActivity() {
    private lateinit var store: ScanSessionStore
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var preview: PreviewView?=null
    private var cornerView: ScanCornerView?=null
    private var compareView: ScanCompareView?=null
    private var displayed: ScanProcessedPage?=null
    private var originalPreview: Bitmap?=null
    private var provider: ProcessCameraProvider?=null
    private var camera: Camera?=null
    private var capture: ImageCapture?=null
    private var analysis: ImageAnalysis?=null
    private val worker=Executors.newSingleThreadExecutor()
    private val liveWorker=Executors.newSingleThreadExecutor()
    private lateinit var engine: ScannerEngine
    private lateinit var liveDetector: DocQuadDetector
    private val gate=StableCaptureGate()
    private var automatic=true
    @Volatile private var cameraVisible=false
    @Volatile private var focused=false
    @Volatile private var occupied=false
    private var lastAnalysis=0L
    private var generation=0
    private var taskToken=0
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) showCamera() else status.text="يمكنك السماح بالكاميرا أو اختيار صورة من المعرض" }
    private val gallery=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) task("حفظ الصور الأصلية وكشف حدود الورقة") {
            var last: ScanPage?=null
            for(uri in uris.take(20-store.visiblePages.size)) { val page=store.import(uri);engine.detect(store,page);last=page }
            done { last?.let { showCorners(it) } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine=ScannerEngine(applicationContext);liveDetector=DocQuadDetector(applicationContext)
        store=runCatching { ScanSessionStore.open(this,savedInstanceState?.getString("session") ?: intent.getStringExtra(EXTRA_SESSION).orEmpty()) }
            .getOrElse { ScanSessionStore.create(this) }
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(20,25,29)) }
        setContentView(root);enableEdgeToEdge()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets }
        if(store.visiblePages.isNotEmpty()) showCorners(store.visiblePages.last()) else showCamera()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("session",store.id);super.onSaveInstanceState(outState) }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).roundToInt()
    private fun text(value: String,size: Float=16f)=TextView(this).apply { text=value;textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),dp(6),dp(10),dp(6)) }
    private fun button(label: String,tagName: String?=null,action: ()->Unit)=Button(this).apply {
        text=label;textSize=13f;isAllCaps=false;minHeight=dp(48);tag=tagName;setOnClickListener { if(!occupied) action() } }
    private fun row(vararg views: View) { root.addView(LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL;views.forEach { addView(it,LinearLayout.LayoutParams(0,dp(52),1f)) } }) }
    private fun clearScreen(title: String) {
        generation++;stopCamera();cornerView?.bitmap=null;cornerView=null
        compareView?.before=null;compareView?.after=null;compareView=null
        displayed?.close();displayed=null;originalPreview?.recycle();originalPreview=null
        root.removeAllViews();root.addView(text(title,18f));status=text("");root.addView(status)
    }
    private fun showCamera() {
        clearScreen("مسح المستند • "+store.visiblePages.size+"/20 صفحة")
        if(store.visiblePages.size>=20) { showPages();return }
        val box=FrameLayout(this);val view=PreviewView(this).apply { implementationMode=PreviewView.ImplementationMode.COMPATIBLE;scaleType=PreviewView.ScaleType.FILL_CENTER }
        preview=view;box.addView(view,FrameLayout.LayoutParams(-1,-1));val overlay=ScanLiveOverlay(this);box.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        root.addView(box,LinearLayout.LayoutParams(-1,0,1f));status.text="ضع الورقة كاملة داخل الكاميرا، مع مساحة صغيرة حول حوافها"
        row(button("تصوير","scanner-capture") { takePhoto() },button("المعرض","scanner-gallery") { gallery.launch(arrayOf("image/*")) },
            button("الصفحات ("+store.visiblePages.size+")","scanner-pages") { showPages() })
        root.addView(CheckBox(this).apply { text="التقاط تلقائي عند ثبات الزوايا ووضوح النص";setTextColor(Color.WHITE);isChecked=automatic
            setOnCheckedChangeListener { _,checked -> automatic=checked;gate.reset() } })
        root.addView(button("استخدام السكانر السابق") { setResult(RESULT_FIRST_USER);finish() })
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA);return
        }
        cameraVisible=true;val request=generation
        view.post {
            if(request!=generation || !cameraVisible) return@post
            val future=ProcessCameraProvider.getInstance(this)
            future.addListener({
                if(request!=generation || !cameraVisible || isDestroyed) return@addListener
                try {
                    val p=future.get();provider=p;val rotation=view.display?.rotation ?: Surface.ROTATION_0
                    val live=Preview.Builder().setTargetRotation(rotation).build().also { it.surfaceProvider=view.surfaceProvider }
                    val photoBuilder=ImageCapture.Builder().setTargetRotation(rotation).setJpegQuality(100).setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
                    val info=p.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK }
                    val fixed=(info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) } ?: 1f)==0f
                    focused=fixed
                    val callback=object: CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession,request: CaptureRequest,result: TotalCaptureResult) {
                            val af=result.get(CaptureResult.CONTROL_AF_STATE)
                            focused=fixed || af==CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED || af==CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        }
                    }
                    if(Build.VERSION.SDK_INT>=28) {
                        val modes=info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) }
                        if(modes?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)==true)
                            Camera2Interop.Extender(photoBuilder).setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE,CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
                    }
                    val analyzerBuilder=ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640,480),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    Camera2Interop.Extender(analyzerBuilder).setSessionCaptureCallback(callback)
                    val analyzer=analyzerBuilder.build();analysis=analyzer;val photo=photoBuilder.build();capture=photo
                    analyzer.setAnalyzer(liveWorker) { proxy -> analyze(proxy,view,overlay,request) }
                    val group=UseCaseGroup.Builder().addUseCase(live).addUseCase(photo).addUseCase(analyzer)
                    view.viewPort?.let { group.setViewPort(it) };p.unbindAll()
                    camera=p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group.build());camera?.cameraControl?.setZoomRatio(1f)
                    view.setOnTouchListener { v,event ->
                        if(event.action==MotionEvent.ACTION_UP) { v.performClick();camera?.cameraControl?.startFocusAndMetering(
                            FocusMeteringAction.Builder(view.meteringPointFactory.createPoint(event.x,event.y)).setAutoCancelDuration(3,TimeUnit.SECONDS).build()) };true
                    }
                } catch(_: Exception) { status.text="الكاميرا غير متاحة حاليًا؛ اختر صورة من المعرض أو السكانر السابق";stopCamera() }
            },ContextCompat.getMainExecutor(this))
        }
    }
    private fun analyze(proxy: ImageProxy,view: PreviewView,overlay: ScanLiveOverlay,request: Int) {
        try {
            val now=SystemClock.elapsedRealtime();if(!cameraVisible || occupied || now-lastAnalysis<140) return
            lastAnalysis=now;val bitmap=analysisBitmap(proxy)
            val result: DocumentDetection;val quality: CaptureQuality
            try { result=liveDetector.detect(bitmap);quality=ScanQuality.analyze(bitmap,result.quad,focused) } finally { bitmap.recycle() }
            val stable=gate.observe(result.quad,quality,now)
            val w=if(proxy.imageInfo.rotationDegrees%180==0) proxy.cropRect.width() else proxy.cropRect.height()
            val h=if(proxy.imageInfo.rotationDegrees%180==0) proxy.cropRect.height() else proxy.cropRect.width()
            val points=result.quad?.points?.flatMap { listOf((it.x*w).toFloat(),(it.y*h).toFloat()) }?.toFloatArray()
            val transform=ImageProxyTransformFactory().apply { isUsingCropRect=true;isUsingRotationDegrees=true }.getOutputTransform(proxy)
            ui {
                if(request!=generation || !cameraVisible) return@ui
                val destination=view.outputTransform
                if(points!=null && destination!=null) { CoordinateTransform(transform,destination).mapPoints(points);overlay.points=points } else overlay.points=null
                status.text=if(result.quad==null) "ضع الورقة كاملة داخل الإطار" else if(!result.quad.fullyVisible()) "أبعد الهاتف قليلًا حتى تظهر جميع الحواف"
                    else if(result.quad.confidence<.78) "حدود الورقة غير واضحة؛ غيّر الخلفية أو الإضاءة" else quality.guidance()
                if(automatic && stable && !occupied) takePhoto()
            }
        } catch(_: Exception) { gate.reset() }
        catch(_: OutOfMemoryError) { gate.reset();ui { stopCamera();status.text="الذاكرة مشغولة؛ اختر صورة من المعرض أو أعد المحاولة" } }
        finally { proxy.close() }
    }
    private fun analysisBitmap(proxy: ImageProxy): Bitmap {
        val rect=proxy.cropRect;val plane=proxy.planes[0];val buffer=plane.buffer;val pixels=IntArray(rect.width()*rect.height())
        // Camera-core RGBA is R,G,B,A; explicit reads handle row padding.
        for(y in 0 until rect.height()) for(x in 0 until rect.width()) {
            val i=(rect.top+y)*plane.rowStride+(rect.left+x)*plane.pixelStride
            pixels[y*rect.width()+x]=Color.argb(buffer.get(i+3).toInt() and 255,buffer.get(i).toInt() and 255,buffer.get(i+1).toInt() and 255,buffer.get(i+2).toInt() and 255)
        }
        val bitmap=createBitmap(rect.width(),rect.height());bitmap.setPixels(pixels,0,rect.width(),0,0,rect.width(),rect.height())
        val angle=proxy.imageInfo.rotationDegrees;if(angle==0) return bitmap
        val rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(angle.toFloat()) },false)
        if(rotated!==bitmap) bitmap.recycle();return rotated
    }
    private fun takePhoto() {
        val photo=capture ?: return;if(occupied || !cameraVisible) return
        occupied=true;gate.reset();status.text="جارٍ حفظ الصورة الأصلية"
        val target=File(cacheDir,"scan-capture-"+java.util.UUID.randomUUID()+".jpg")
        photo.takePicture(ImageCapture.OutputFileOptions.Builder(target).build(),ContextCompat.getMainExecutor(this),object: ImageCapture.OnImageSavedCallback {
            override fun onError(error: ImageCaptureException) { occupied=false;target.delete();status.text="تعذر التصوير؛ حاول مرة أخرى" }
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                occupied=false;stopCamera()
                task("كشف الورقة وتدقيق الحواف") { val page=target.inputStream().use { store.import(it) };target.delete();engine.detect(store,page);done { showCorners(page) } }
            }
        })
    }
    private fun stopCamera() { cameraVisible=false;analysis?.clearAnalyzer();provider?.unbindAll();analysis=null;capture=null;camera=null;preview=null;gate.reset() }
    private fun showCorners(page: ScanPage) {
        clearScreen("زوايا الورقة • "+(store.visiblePages.indexOf(page)+1)+"/"+store.visiblePages.size)
        status.text=if(page.review) "راجع الزوايا جيدًا؛ الكشف غير مؤكد. كبّر بإصبعين واسحب النقاط" else "راجع القص، كبّر بإصبعين واسحب أي زاوية تحتاج تعديلًا"
        val editor=ScanCornerView(this);cornerView=editor;root.addView(editor,LinearLayout.LayoutParams(-1,0,1f))
        editor.quad=page.quad;editor.onChanged={ page.quad=it;page.ready=false;page.review=false;store.save() }
        task("تحميل الصورة الأصلية") { val bitmap=ScanSourceImage.preview(store.source(page),page.turns,2400)
            done(onDiscard={bitmap.recycle()}) { if(editor!==cornerView) bitmap.recycle() else { originalPreview=bitmap;editor.bitmap=bitmap;editor.quad=page.quad
                status.text=if(page.review) "لم نتأكد من كل الحواف؛ كبّر وراجع الزوايا الأربع قبل تطبيق القص" else "راجع الزوايا ثم اضغط تطبيق" } } }
        row(button("كشف جديد") { task("إعادة كشف الحواف") { engine.detect(store,page);done { editor.quad=page.quad
            status.text=if(page.review) "لم نتأكد من كل الحواف؛ راجع الزوايا الأربع" else "راجع الزوايا المكتشفة" } } },
            button("تدوير") { page.turns=(page.turns+1)%4;page.ready=false;task("تدوير وكشف الورقة") { engine.detect(store,page);done { showCorners(page) } } },
            button("الصورة كاملة") { page.quad=DocumentQuad.inset(0.0).copy(confidence=1.0,origin="manual");page.ready=false;editor.quad=page.quad;store.save() })
        row(button("تطبيق القص","scanner-apply-crop") { page.quad=editor.quad;page.review=false;page.ready=false;store.save();process(page) },
            button("المقاس") { chooseRatio(page) },button("إضافة") { showCamera() })
    }
    private fun chooseRatio(page: ScanPage) {
        val choices=arrayOf("تلقائي / إيصال / مقاس حر","A4","Letter","بطاقة أفقية","بطاقة عمودية","نسبة مخصصة")
        android.app.AlertDialog.Builder(this).setTitle("مقاس الورقة").setItems(choices) { _,which ->
            if(which==5) {
                val field=EditText(this).apply { hint="العرض ÷ الارتفاع، مثل 0.7071";inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
                android.app.AlertDialog.Builder(this).setTitle("نسبة العرض إلى الارتفاع").setView(field)
                    .setPositiveButton("تطبيق") { _,_ -> val ratio=field.text.toString().toDoubleOrNull()
                        if(ratio!=null && ratio in .03..35.0) { page.paperRatio=ratio;page.ready=false;store.save();status.text="تم اختيار المقاس؛ اضغط تطبيق القص" }
                        else Toast.makeText(this,"أدخل نسبة بين 0.03 و35",Toast.LENGTH_LONG).show() }.setNegativeButton("إلغاء",null).show()
            } else {
                page.paperRatio=when(which) { 1->210.0/297;2->8.5/11;3->85.6/53.98;4->53.98/85.6;else->null }
                page.ready=false;store.save();status.text="تم اختيار المقاس؛ اضغط تطبيق القص"
            }
        }.show()
    }
    private fun process(page: ScanPage) {
        cornerView?.bitmap=null;originalPreview?.recycle();originalPreview=null
        compareView?.before=null;compareView?.after=null;displayed?.close();displayed=null
        task("معالجة الورقة") {
            try { val result=engine.process(store,page) { message -> ui { status.text=message } };done(onDiscard={result.close()}) { showProcessed(page,result) } }
            catch(error: Exception) { done { showCorners(page);status.text=error.localizedMessage ?: "راجع زوايا الورقة" } }
        }
    }
    private fun showProcessed(page: ScanPage,result: ScanProcessedPage) {
        clearScreen("معاينة المسح • "+(store.visiblePages.indexOf(page)+1)+"/"+store.visiblePages.size);displayed=result
        val compare=ScanCompareView(this);compareView=compare;compare.before=result.before;compare.after=result.after
        root.addView(compare,LinearLayout.LayoutParams(-1,0,1f))
        status.text=if(result.warnings.isEmpty()) "اسحب الخط للمقارنة. الصورة الأصلية محفوظة" else result.warnings.take(2).joinToString("\n")
        val filters=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        ScanFilter.entries.forEach { mode -> filters.addView(button((if(page.filter==mode) "✓ " else "")+mode.title,"filter-"+mode.name) { page.filter=mode;page.ready=false;store.save();process(page) }) }
        root.addView(HorizontalScrollView(this).apply { addView(filters) })
        row(button("تعديل الزوايا") { showCorners(page) },button("الأصل الكامل") { showRaw(page) },
            button(if(page.dewarp) "التسطيح المتقدم ✓" else "التسطيح المتقدم") { page.dewarp=!page.dewarp;page.ready=false;store.save();process(page) })
        row(button("إضافة صفحة") { showCamera() },button("الصفحات") { showPages() },button("حفظ PDF","scanner-save") { finishScan() })
    }
    private fun showRaw(page: ScanPage) {
        clearScreen("الصورة الأصلية المحفوظة");val editor=ScanCornerView(this);cornerView=editor;editor.quad=DocumentQuad.inset(0.0)
        root.addView(editor,LinearLayout.LayoutParams(-1,0,1f));status.text="الأصل محفوظ دون تغيير؛ يمكنك تكبير المعاينة"
        task("تحميل الأصل") { val bitmap=ScanSourceImage.preview(store.source(page),0,3000);done(onDiscard={bitmap.recycle()}) { if(editor!==cornerView) bitmap.recycle() else { originalPreview=bitmap;editor.bitmap=bitmap } } }
        row(button("عودة للقص") { showCorners(page) },button("مشاركة الأصل") {
            val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.files",store.source(page))
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"الصورة الأصلية")) })
    }
    private fun showPages() {
        clearScreen("صفحات المستند • "+store.visiblePages.size);status.text="راجع أي صفحة، رتّب الصفحات أو أضف صورة"
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },LinearLayout.LayoutParams(-1,0,1f))
        store.visiblePages.forEachIndexed { index,page ->
            val line=LinearLayout(this);line.addView(button((index+1).toString()+" • "+if(page.ready) "جاهزة" else "تحتاج مراجعة") { showCorners(page) },LinearLayout.LayoutParams(0,dp(56),1f))
            line.addView(button("↑") { val at=store.pages.indexOf(page);val previous=store.pages.take(at).lastOrNull { !it.deleted }
                if(previous!=null) { java.util.Collections.swap(store.pages,at,store.pages.indexOf(previous));store.save();showPages() } })
            line.addView(button("حذف") { page.deleted=true;store.save();showPages() });list.addView(line)
        }
        if(store.pages.any { it.deleted }) list.addView(button("استرجاع الصفحات المحذوفة") { store.pages.forEach { it.deleted=false };store.save();showPages() })
        row(button("إضافة") { showCamera() },button("حفظ PDF","scanner-save") { finishScan() })
    }
    private fun finishScan() {
        if(store.visiblePages.isEmpty()) { status.text="أضف صفحة أولًا";return }
        val pending=store.visiblePages.firstOrNull { !it.ready || it.review }
        if(pending!=null) { showCorners(pending);status.text="اعتمد قص هذه الصفحة قبل الحفظ";return }
        store.save();setResult(RESULT_OK,Intent().putExtra(EXTRA_SESSION,store.id));finish()
    }
    private fun task(message: String,operation: ()->Unit) {
        if(occupied) return;occupied=true;status.text=message;val ticket=++taskToken
        worker.execute {
            try { operation() } catch(error: Exception) { ui { status.text=error.localizedMessage ?: "تعذر إكمال المعالجة؛ الأصل محفوظ" } }
            catch(_: OutOfMemoryError) { ui { status.text="الذاكرة مشغولة؛ الأصل محفوظ. أعد المحاولة بصورة أصغر" } }
            finally { ui { if(ticket==taskToken) occupied=false } }
        }
    }
    private fun ui(action: ()->Unit) { runOnUiThread { if(!isDestroyed && !isFinishing) action() } }
    private fun done(onDiscard: ()->Unit={},action: ()->Unit) {
        runOnUiThread { if(isDestroyed || isFinishing) onDiscard() else { occupied=false;action() } }
    }
    override fun onDestroy() {
        generation++;stopCamera();cornerView?.bitmap=null;compareView?.before=null;compareView?.after=null
        displayed?.close();displayed=null;originalPreview?.recycle();originalPreview=null
        worker.shutdownNow();liveWorker.shutdownNow()
        Thread {
            worker.awaitTermination(30,TimeUnit.SECONDS);liveWorker.awaitTermination(30,TimeUnit.SECONDS)
            engine.close();liveDetector.close()
        }.start()
        super.onDestroy()
    }
    companion object { const val EXTRA_SESSION="scanner_session_id" }
}
private class ScanLiveOverlay(context: android.content.Context): View(context) {
    var points: FloatArray?=null;set(value) { field=value;invalidate() }
    private val path=Path();private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(33,218,181);style=Paint.Style.STROKE;strokeWidth=resources.displayMetrics.density*3 }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);val p=points ?: return;if(p.size!=8) return
        path.reset();path.moveTo(p[0],p[1]);for(i in 1..3) path.lineTo(p[i*2],p[i*2+1]);path.close();canvas.drawPath(path,paint) }
}
