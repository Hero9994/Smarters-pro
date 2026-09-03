package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object DocumentImageEnhancer {
    fun processPagesToPdf(
        context: Context,
        pageUris: List<Uri>,
        target: File,
        onPageReady: (index: Int, bitmap: Bitmap) -> Unit = { _, _ -> }
    ): Int {
        require(pageUris.isNotEmpty()) { "No scanned pages" }
        val pdf = PdfDocument()
        var written = 0
        try {
            pageUris.forEachIndexed { index, uri ->
                val source = decodeSampled(context, uri, 2200)
                    ?: throw IllegalStateException("Cannot decode scanned page ${index + 1}")
                val cleaned = flattenDocumentShadows(source)
                if (cleaned !== source) source.recycle()
                try {
                    onPageReady(index, cleaned)
                    val pageInfo = PdfDocument.PageInfo.Builder(cleaned.width, cleaned.height, index + 1).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(cleaned, 0f, 0f, null)
                    pdf.finishPage(page)
                    written++
                } finally {
                    cleaned.recycle()
                }
            }
            target.parentFile?.mkdirs()
            target.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return written
    }

    private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    internal fun flattenDocumentShadows(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 120 || height < 120) return source.copy(Bitmap.Config.ARGB_8888, false)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val tile = max(56, min(width, height) / 22)
        val cols = ceil(width.toDouble() / tile).toInt().coerceAtLeast(1)
        val rows = ceil(height.toDouble() / tile).toInt().coerceAtLeast(1)
        val backgrounds = FloatArray(cols * rows)
        val sampleStep = max(2, tile / 28)

        for (ty in 0 until rows) {
            val yStart = ty * tile
            val yEnd = min(height, yStart + tile)
            for (tx in 0 until cols) {
                val xStart = tx * tile
                val xEnd = min(width, xStart + tile)
                val histogram = IntArray(32)
                var count = 0
                var y = yStart
                while (y < yEnd) {
                    var x = xStart
                    while (x < xEnd) {
                        val c = pixels[y * width + x]
                        val lum = luminance(c)
                        histogram[(lum * 31 / 255).coerceIn(0, 31)]++
                        count++
                        x += sampleStep
                    }
                    y += sampleStep
                }
                val wanted = (count * 0.86f).toInt().coerceAtLeast(1)
                var cumulative = 0
                var bin = 31
                for (i in histogram.indices) {
                    cumulative += histogram[i]
                    if (cumulative >= wanted) {
                        bin = i
                        break
                    }
                }
                backgrounds[ty * cols + tx] = ((bin + 0.5f) * 255f / 32f).coerceAtLeast(90f)
            }
        }

        val out = IntArray(pixels.size)
        val targetPaper = 244f
        for (y in 0 until height) {
            val gy = (y.toFloat() / tile - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val y0 = gy.toInt().coerceIn(0, rows - 1)
            val y1 = min(rows - 1, y0 + 1)
            val wy = gy - y0
            for (x in 0 until width) {
                val gx = (x.toFloat() / tile - 0.5f).coerceIn(0f, (cols - 1).toFloat())
                val x0 = gx.toInt().coerceIn(0, cols - 1)
                val x1 = min(cols - 1, x0 + 1)
                val wx = gx - x0
                val b00 = backgrounds[y0 * cols + x0]
                val b10 = backgrounds[y0 * cols + x1]
                val b01 = backgrounds[y1 * cols + x0]
                val b11 = backgrounds[y1 * cols + x1]
                val top = b00 + (b10 - b00) * wx
                val bottom = b01 + (b11 - b01) * wx
                val background = (top + (bottom - top) * wy).coerceAtLeast(80f)

                val c = pixels[y * width + x]
                val a = Color.alpha(c)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = luminance(c).toFloat()
                val paperFactor = (targetPaper / background).coerceIn(0.88f, 1.85f)
                val protectInk = ((lum - 35f) / 170f).coerceIn(0.08f, 1f)
                val scale = 1f + (paperFactor - 1f) * protectInk

                var nr = (r * scale).toInt().coerceIn(0, 255)
                var ng = (g * scale).toInt().coerceIn(0, 255)
                var nb = (b * scale).toInt().coerceIn(0, 255)
                val newLum = (0.299f * nr + 0.587f * ng + 0.114f * nb)
                if (newLum > 178f) {
                    val whitePush = ((newLum - 178f) / 77f).coerceIn(0f, 1f) * 0.34f
                    nr = (nr + (255 - nr) * whitePush).toInt().coerceIn(0, 255)
                    ng = (ng + (255 - ng) * whitePush).toInt().coerceIn(0, 255)
                    nb = (nb + (255 - nb) * whitePush).toInt().coerceIn(0, 255)
                }
                out[y * width + x] = Color.argb(a, nr, ng, nb)
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(color: Int): Int =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000)
}
