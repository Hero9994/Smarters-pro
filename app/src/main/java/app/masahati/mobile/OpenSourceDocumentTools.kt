package app.masahati.mobile

import android.content.Context
import android.graphics.Bitmap
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import org.jsoup.Jsoup
import java.io.File
import java.util.EnumMap
import kotlin.math.max

data class AlphaWebClip(
    val url: String,
    val title: String,
    val description: String?,
    val text: String
)

data class AlphaLocalSignals(
    val language: String?,
    val phones: List<String>,
    val barcodes: List<String>
) {
    fun asPromptHint(): String {
        val lines = mutableListOf<String>()
        language?.let { lines += "لغة النص المكتشفة محلياً: $it" }
        if (phones.isNotEmpty()) lines += "أرقام هاتف مؤكدة محلياً: ${phones.joinToString(", ")}"
        if (barcodes.isNotEmpty()) lines += "QR/Barcode مقروء محلياً: ${barcodes.joinToString(" | ")}"
        return lines.joinToString("\n")
    }
}

object OpenSourceDocumentTools {
    private val languageDetector by lazy {
        LanguageDetectorBuilder
            .fromLanguages(Language.ARABIC, Language.GERMAN, Language.ENGLISH)
            .withLowAccuracyMode()
            .build()
    }

    fun detectLanguage(text: String): String? {
        val sample = text.trim().take(6000)
        if (sample.length < 24) return null
        return when (runCatching { languageDetector.detectLanguageOf(sample) }.getOrNull()) {
            Language.ARABIC -> "ar"
            Language.GERMAN -> "de"
            Language.ENGLISH -> "en"
            else -> null
        }
    }

    fun extractPhones(context: Context, text: String, defaultRegion: String = "DE"): List<String> {
        if (text.length < 6) return emptyList()
        return runCatching {
            val util = PhoneNumberUtil.createInstance(context.applicationContext)
            util.findNumbers(text.take(20_000), defaultRegion)
                .mapNotNull { match ->
                    runCatching {
                        util.format(match.number(), PhoneNumberUtil.PhoneNumberFormat.E164)
                    }.getOrNull()
                }
                .distinct()
                .take(12)
                .toList()
        }.getOrDefault(emptyList())
    }

    fun decodeBarcodes(bitmap: Bitmap): List<String> {
        if (bitmap.width < 40 || bitmap.height < 40) return emptyList()
        val maxSide = 1600
        val largest = max(bitmap.width, bitmap.height)
        val working = if (largest > maxSide) {
            val scale = maxSide.toFloat() / largest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bitmap

        return try {
            val pixels = IntArray(working.width * working.height)
            working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
            val source = RGBLuminanceSource(working.width, working.height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.CHARACTER_SET, "UTF-8")
            }
            val reader = GenericMultipleBarcodeReader(MultiFormatReader())
            runCatching { reader.decodeMultiple(binary, hints) }
                .getOrNull()
                .orEmpty()
                .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
                .take(12)
        } finally {
            if (working !== bitmap) working.recycle()
        }
    }

    fun extractPdfText(context: Context, file: File, maxChars: Int = 24_000): String {
        if (!file.isFile || file.length() <= 0L) return ""
        return runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { document ->
                if (document.numberOfPages <= 0) return@use ""
                PDFTextStripper().apply {
                    startPage = 1
                    endPage = minOf(document.numberOfPages, 120)
                }.getText(document)
                    .replace("\u0000", "")
                    .trim()
                    .take(maxChars.coerceIn(1000, 100_000))
            }
        }.getOrDefault("")
    }

    fun clipWebPage(url: String, maxChars: Int = 18_000): AlphaWebClip {
        require(AlphaHttp.isSafeWebUrl(url)) { "الرابط غير مدعوم" }
        val html = AlphaHttp.getText(url)
        val document = Jsoup.parse(html, url)
        document.select("script,style,noscript,svg,canvas,form,nav,footer").remove()
        val title = document.title().trim().take(180).ifBlank {
            runCatching { java.net.URI(url).host }.getOrNull().orEmpty().ifBlank { "صفحة ويب" }
        }
        val description = document
            .selectFirst("meta[name=description],meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.take(500)
            ?.takeIf { it.isNotBlank() }
        val body = document.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return AlphaWebClip(
            url = url,
            title = title,
            description = description,
            text = body.take(maxChars.coerceIn(1000, 80_000))
        )
    }

    fun signals(context: Context, text: String, barcodes: List<String> = emptyList()): AlphaLocalSignals =
        AlphaLocalSignals(
            language = detectLanguage(text),
            phones = extractPhones(context, text),
            barcodes = barcodes.distinct().take(12)
        )

    internal fun findFirstWebUrl(text: String): String? {
        val match = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
        return match.value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '،')
    }
}
