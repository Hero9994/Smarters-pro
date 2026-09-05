package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import kotlin.math.max

object BetaScannerProcessor {
    fun processPagesToPdf(
        context: Context,
        pageUris: List<Uri>,
        target: File,
        mode: BetaScanMode,
        onPageReady: (index: Int, bitmap: Bitmap) -> Unit = { _, _ -> }
    ): Int {
        require(pageUris.isNotEmpty()) { "No scanned pages" }
        val pdf = PdfDocument()
        var written = 0
        try {
            pageUris.forEachIndexed { index, uri ->
                val source = decodeSampled(context, uri, 2200)
                    ?: throw IllegalStateException("Cannot decode scanned page ${index + 1}")
                var processed: Bitmap = source
                try {
                    processed = when (mode) {
                        BetaScanMode.ORIGINAL -> source
                        BetaScanMode.BALANCED -> DocumentImageEnhancer.flattenDocumentShadows(source)
                        BetaScanMode.STRONG -> strongClean(source)
                        BetaScanMode.BLACK_WHITE -> toBlackWhite(strongClean(source))
                    }
                    onPageReady(index, processed)
                    val pageInfo = PdfDocument.PageInfo.Builder(processed.width, processed.height, index + 1).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(processed, 0f, 0f, null)
                    pdf.finishPage(page)
                    written++
                } finally {
                    if (processed !== source && !processed.isRecycled) processed.recycle()
                    if (!source.isRecycled) source.recycle()
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

    private fun strongClean(source: Bitmap): Bitmap {
        val balanced = DocumentImageEnhancer.flattenDocumentShadows(source)
        val width = balanced.width
        val height = balanced.height
        val pixels = IntArray(width * height)
        balanced.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (i in pixels.indices step 5) histogram[luminance(pixels[i])]++
        val total = histogram.sum().coerceAtLeast(1)
        var cumulative = 0
        var p88 = 235
        val wanted = (total * 0.88f).toInt()
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= wanted) {
                p88 = i
                break
            }
        }
        val paperTarget = (p88 + 20).coerceIn(238, 252)
        val out = IntArray(pixels.size)

        for (i in pixels.indices) {
            val c = pixels[i]
            val lum = luminance(c).coerceAtLeast(1)
            val protectInk = ((lum - 45f) / 145f).coerceIn(0f, 1f)
            val desired = (lum + (paperTarget - lum) * 0.42f * protectInk).coerceIn(0f, 253f)
            val scale = (desired / lum).coerceIn(0.90f, 1.85f)
            val r = (Color.red(c) * scale).toInt().coerceIn(0, 255)
            val g = (Color.green(c) * scale).toInt().coerceIn(0, 255)
            val b = (Color.blue(c) * scale).toInt().coerceIn(0, 255)
            out[i] = Color.argb(Color.alpha(c), r, g, b)
        }

        val result = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        if (balanced !== source && !balanced.isRecycled) balanced.recycle()
        return result
    }

    private fun toBlackWhite(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        pixels.forEach { histogram[luminance(it)]++ }

        val total = pixels.size.toLong()
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * histogram[i]
        var sumBackground = 0L
        var weightBackground = 0L
        var bestVariance = -1.0
        var threshold = 190
        for (t in 0..255) {
            weightBackground += histogram[t].toLong()
            if (weightBackground == 0L) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0L) break
            sumBackground += t.toLong() * histogram[t]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val variance = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = t
            }
        }
        threshold = threshold.coerceIn(145, 220)

        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val lum = luminance(pixels[i])
            val value = if (lum >= threshold) 255 else 0
            out[i] = Color.rgb(value, value, value)
        }
        val result = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        if (!source.isRecycled) source.recycle()
        return result
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
}
