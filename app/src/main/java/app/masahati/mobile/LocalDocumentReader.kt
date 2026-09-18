package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** Owned by the activity's serial worker. No image or PDF is uploaded by this reader. */
class LocalDocumentReader(context: Context) : Closeable {
    private val app = context.applicationContext
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var tess: TessBaseAPI? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val stopLock = Any()

    private fun arabicEngine(): TessBaseAPI {
        tess?.let { return it }
        val root = File(app.noBackupFilesDir, "ocr-v1")
        val data = File(root, "tessdata").apply { mkdirs() }
        val hashes = mapOf(
            "ara" to "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729",
            "deu" to "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
            "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        )
        for ((language, expected) in hashes) {
            val file = File(data, "$language.traineddata")
            if (!file.isFile || sha256(file) != expected) {
                val temp = File(data, "$language.tmp")
                try {
                    app.assets.open("ocr/tessdata/$language.traineddata").use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    }
                    check(sha256(temp) == expected) { "ملف قراءة العربية غير مكتمل" }
                    check(temp.renameTo(file)) { "تعذر تجهيز قارئ النصوص" }
                } finally { temp.delete() }
            }
        }
        val engine = TessBaseAPI()
        try {
            check(engine.init(root.absolutePath, "ara+deu+eng", TessBaseAPI.OEM_LSTM_ONLY))
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            tess = engine
            return engine
        } catch (error: Exception) {
            engine.recycle()
            throw error
        }
    }

    fun readImage(file: File): DocumentReadResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return DocumentReadResult("", "تعذر فتح الصورة للقراءة.")
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return DocumentReadResult("", "تعذر فتح الصورة للقراءة.")
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val oriented = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return try { readBitmap(oriented) } finally {
            if (oriented !== bitmap) oriented.recycle()
            bitmap.recycle()
        }
    }

    fun readBitmap(bitmap: Bitmap): DocumentReadResult {
        val scale = minOf(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val working = if (scale < 1f) bitmap.scale(
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
        try {
            var timedOut = false
            var engineFailed = false
            var confidence = 0
            val multilingual = try {
                val engine = arabicEngine()
                engine.setImage(working)
                var active = true
                val timeout = timer.schedule({
                    synchronized(stopLock) {
                        if (active) { timedOut = true; engine.stop() }
                    }
                }, 15, TimeUnit.SECONDS)
                try {
                    // HOCR uses Tesseract's cancellation monitor; getUTF8Text alone does not.
                    engine.getHOCRText(0)
                    synchronized(stopLock) { active = false }
                    timeout.cancel(false)
                    if (timedOut || Thread.currentThread().isInterrupted) "" else {
                        confidence = engine.meanConfidence()
                        engine.getUTF8Text().orEmpty().trim()
                    }
                } finally {
                    synchronized(stopLock) { active = false }
                    timeout.cancel(false)
                    engine.clear()
                }
            } catch (_: Exception) { engineFailed = true; "" }
            // The multilingual engine retains the layout of mixed Arabic/Latin pages.
            val hasArabic = multilingual.count { it in '\u0600'..'\u06FF' && it.isLetter() } >= 3
            val selected = if (hasArabic && confidence >= 20) multilingual else {
                val latinText = try {
                    Tasks.await(latin.process(InputImage.fromBitmap(working, 0)), 12, TimeUnit.SECONDS).text.trim()
                } catch (_: Exception) { "" }
                latinText.ifBlank { multilingual.takeIf { confidence >= 20 }.orEmpty() }
            }
            val note = when {
                engineFailed -> "تعذر تشغيل قارئ العربية؛ قد يكون النص المقروء ناقصاً."
                timedOut -> "لم تكتمل قراءة الصورة ضمن المهلة؛ راجع النص المقروء."
                selected.isBlank() -> "لم أجد نصاً واضحاً في الصورة؛ قد تكون فارغة أو تحتاج صورة أوضح."
                hasArabic && confidence < 55 -> "بعض الكلمات غير واضحة؛ راجع الأسماء والأرقام في الأصل."
                else -> null
            }
            return DocumentReadResult(selected.take(MAX_CHARS), note ?: if (selected.length > MAX_CHARS) "النص طويل؛ حُفظ أول $MAX_CHARS حرف للبحث." else null, ocrPages = 1)
        } finally { if (working !== bitmap) working.recycle() }
    }

    fun readPdf(file: File, maxPages: Int = 120, maxOcrPages: Int = 20, maxChars: Int = MAX_CHARS): DocumentReadResult {
        if (!file.isFile || file.length() > 160L * 1024L * 1024L) return DocumentReadResult("", "حجم الملف يتجاوز حد القراءة المحلية.", processedPages = 0)
        PDFBoxResourceLoader.init(app)
        var document: PDDocument? = null
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val notes = linkedSetOf<String>()
        val result = StringBuilder()
        var total = 0
        var processed = 0
        var ocrCount = 0
        val started = SystemClock.elapsedRealtime()
        try {
            document = runCatching { PDDocument.load(file) }.getOrNull()
            runCatching {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(descriptor!!)
            }
            total = maxOf(document?.numberOfPages ?: 0, renderer?.pageCount ?: 0)
            if (total == 0) return DocumentReadResult("", "تعذر قراءة PDF؛ قد يكون محمياً بكلمة مرور أو تالفاً.", totalPages = 0, processedPages = 0)
            val stripper = PDFTextStripper()
            for (index in 0 until minOf(total, maxPages.coerceAtLeast(0))) {
                if (Thread.currentThread().isInterrupted || SystemClock.elapsedRealtime() - started > 90_000 || result.length >= maxChars) break
                val embedded = runCatching {
                    document?.let {
                        stripper.startPage = index + 1
                        stripper.endPage = index + 1
                        stripper.getText(it).replace("\u0000", "").trim()
                    }.orEmpty()
                }.getOrDefault("")
                val hasImage = runCatching { document?.getPage(index)?.resources?.let { hasLargeImage(it) } == true }.getOrDefault(true)
                var pageText = embedded
                if (!DocumentTextMerge.useful(embedded) || hasImage) {
                    if (ocrCount >= maxOcrPages || renderer == null) {
                        notes += "بعض صفحات الصور لم تُقرأ؛ حد القراءة $maxOcrPages صفحة مصوّرة لكل ملف."
                    } else {
                        try {
                            val reading = renderer!!.openPage(index).use { page ->
                                val factor = minOf(3f, MAX_SIDE.toFloat() / max(page.width, page.height))
                                val bitmap = createBitmap((page.width * factor).roundToInt().coerceAtLeast(1),
                                    (page.height * factor).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                try {
                                    bitmap.eraseColor(Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ocrCount++
                                    readBitmap(bitmap)
                                } finally { bitmap.recycle() }
                            }
                            pageText = DocumentTextMerge.merge(embedded, reading.text)
                            reading.note?.let { notes += "صفحة ${index + 1}: $it" }
                        } catch (_: Exception) { notes += "تعذرت قراءة صورة الصفحة ${index + 1}." }
                    }
                }
                processed++
                if (pageText.isNotBlank()) {
                    val chunk = "${if (result.isEmpty()) "" else "\n\n"}صفحة ${index + 1}:\n$pageText"
                    val remaining = (maxChars - result.length).coerceAtLeast(0)
                    result.append(chunk.take(remaining))
                    if (chunk.length > remaining) notes += "النص طويل؛ حُفظ أول $maxChars حرف للبحث."
                }
            }
            if (processed < total) notes += "قراءة جزئية: عولجت $processed من $total صفحة؛ بقية الملف محفوظة في الأصل."
            if (result.isEmpty()) notes += "لم يُستخرج نص واضح؛ افتح الأصل للمراجعة."
        } catch (_: Exception) {
            notes += "لم تكتمل قراءة الملف؛ النص المتاح قد يكون ناقصاً."
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            runCatching { document?.close() }
        }
        return DocumentReadResult(result.toString(), notes.take(4).joinToString("\n").takeIf(String::isNotBlank), total, processed, ocrCount)
    }

    private fun hasLargeImage(resources: PDResources, depth: Int = 0): Boolean {
        if (depth > 4) return true
        return resources.xObjectNames.any { name ->
            when (val item = resources.getXObject(name)) {
                is PDImageXObject -> item.width >= 300 && item.height >= 300
                is PDFormXObject -> item.resources?.let { hasLargeImage(it, depth + 1) } == true
                else -> false
            }
        }
    }

    override fun close() {
        timer.shutdownNow()
        tess?.recycle()
        tess = null
        latin.close()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32_768)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_SIDE = 2400
        const val MAX_CHARS = 24_000
    }
}
