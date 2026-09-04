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
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    internal fun flattenDocumentShadows(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 120 || height < 120) return source

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
                        val lum = luminance(pixels[y * width + x])
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
                backgrounds[ty * cols + tx] = ((bin + 0.5f) * 255f / 32f).coerceAtLeast(70f)
            }
        }

        val sorted = backgrounds.copyOf().apply { sort() }
        fun percentile(p: Float): Float {
            if (sorted.isEmpty()) return 255f
            val index = (sorted.lastIndex * p).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        val p10 = percentile(0.10f)
        val p50 = percentile(0.50f)
        val p90 = percentile(0.90f)
        val variation = p90 - p10
        val shadowTiles = backgrounds.count { it < p90 - 28f }
        val minShadowTiles = max(2, backgrounds.size / 18)
        val needsCorrection = variation >= max(22f, p50 * 0.11f) && shadowTiles >= minShadowTiles

        if (!needsCorrection) return source

        val targetPaper = (p90 + 8f).coerceIn(225f, 247f)
        val out = IntArray(pixels.size)

        fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

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
                val background = (top + (bottom - top) * wy).coerceAtLeast(65f)

                val pixel = pixels[y * width + x]
                val alpha = Color.alpha(pixel)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                val lum = luminance(pixel).toFloat().coerceAtLeast(1f)

                val localFactor = (targetPaper / background).coerceIn(1f, 1.72f)
                val inkProtection = smoothStep(58f, 178f, lum)
                val localShadowStrength = ((p90 - background) / max(1f, variation)).coerceIn(0f, 1f)
                val correctionWeight = inkProtection * (0.72f + 0.28f * localShadowStrength)
                var correctedLum = lum + (lum * localFactor - lum) * correctionWeight

                if (correctedLum > 205f && localShadowStrength > 0.15f) {
                    val whitePush = smoothStep(205f, 248f, correctedLum) * 0.22f * localShadowStrength
                    correctedLum += (252f - correctedLum) * whitePush
                }
                correctedLum = correctedLum.coerceIn(0f, 252f)

                val scale = (correctedLum / lum).coerceIn(0.92f, 1.78f)
                val nr = (red * scale).toInt().coerceIn(0, 255)
                val ng = (green * scale).toInt().coerceIn(0, 255)
                val nb = (blue * scale).toInt().coerceIn(0, 255)
                out[y * width + x] = Color.argb(alpha, nr, ng, nb)
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(color: Int): Int =
        ((Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000)
}
