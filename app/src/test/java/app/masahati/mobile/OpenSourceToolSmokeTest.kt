package app.masahati.mobile

import androidx.metrics.performance.JankStats
import com.github.anrwatchdog.ANRWatchDog
import com.github.pemistahl.lingua.api.Language
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.text.similarity.LevenshteinDistance
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OpenSourceToolSmokeTest {
    @Test
    fun allTenLibrariesLoadAndCorePureFunctionsWork() {
        // 1 OkHttp
        val httpClient = OkHttpClient()
        assertNotNull(httpClient.dispatcher)
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()

        // 2 jsoup
        val document = Jsoup.parse("<html><head><title>Masahati</title></head><body><p>Hello</p></body></html>")
        assertEquals("Masahati", document.title())
        assertEquals("Hello", document.selectFirst("p")?.text())

        // 3 ZXing
        val matrix = QRCodeWriter().encode("MASAHATI-QR-OK", BarcodeFormat.QR_CODE, 128, 128)
        val pixels = IntArray(128 * 128)
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                pixels[y * 128 + x] = if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
        }
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(128, 128, pixels)))
        )
        assertEquals("MASAHATI-QR-OK", decoded.text)

        // 4 libphonenumber-android
        assertTrue(PhoneNumberUtil::class.java.name.contains("PhoneNumberUtil"))

        // 5 Commons Text
        assertEquals(1, LevenshteinDistance().apply("rechnung", "rechnunq"))

        // 6 Commons Compress
        val zipBytes = ByteArrayOutputStream().also { out ->
            ZipArchiveOutputStream(out).use { zip ->
                zip.putArchiveEntry(ZipArchiveEntry("x.txt"))
                zip.write("ok".toByteArray())
                zip.closeArchiveEntry()
            }
        }.toByteArray()
        ZipArchiveInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            assertEquals("x.txt", zip.nextEntry?.name)
            assertEquals("ok", zip.readBytes().toString(Charsets.UTF_8))
        }

        // 7 Lingua
        val detector = LanguageDetectorBuilder
            .fromLanguages(Language.ARABIC, Language.GERMAN, Language.ENGLISH)
            .withLowAccuracyMode()
            .build()
        assertEquals(
            Language.GERMAN,
            detector.detectLanguageOf("Dieser Vertrag enthält wichtige Informationen über Frist und Zahlung.".repeat(3))
        )

        // 8 PDFBox Android
        PDDocument().use { pdf ->
            assertEquals(0, pdf.numberOfPages)
        }

        // 9 JankStats
        assertTrue(JankStats::class.java.name.contains("JankStats"))

        // 10 ANR-WatchDog
        assertEquals("ANRWatchDog", ANRWatchDog::class.java.simpleName)
    }
}
